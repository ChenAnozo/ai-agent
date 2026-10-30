package com.yupi.yuaiagent.reading.knowledge;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.book.BookSearch;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

class KnowledgeServiceTest {
    @TempDir
    Path directory;

    private com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbEntity knowledgeBase(String id, Path file) {
        var kb = new com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbEntity();
        kb.setId(id);
        kb.setPath(file.toString());
        kb.setFormat("epub");
        kb.setGeneration("gen");
        return kb;
    }

    @Test
    void largeBookIsEmbeddedWithinProviderBatchLimitWithoutLosingChunks() throws Exception {
        var store = mock(ReadingStore.class);
        String id = UUID.randomUUID().toString();
        Path file = directory.resolve("large-book.epub");
        when(store.knowledgeBase(id)).thenReturn(knowledgeBase(id, file));
        when(store.vectorDimensions(id)).thenReturn(3);
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < 23; i++) sections.add("章节原文 " + i);
        var parsers = mock(DocumentParsers.class);
        when(parsers.parseDocument(file, "epub")).thenReturn(new DocumentParsers.ParsedDocument(sections, Map.of("dc:title", List.of("测试书籍"))));
        var vectors = mock(PgVectorStore.class);
        var texts = mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class);
        List<String> vectorIds = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>(), ordinals = new ArrayList<>();
        doAnswer(invocation -> {
            List<org.springframework.ai.document.Document> batch = invocation.getArgument(0);
            if (batch.size() > 10) throw new IllegalArgumentException("batch size should not be larger than 10");
            batchSizes.add(batch.size());
            for (var document : batch) {
                assertEquals(List.of("测试书籍"), document.getMetadata().get("document.dc:title"));
                assertEquals(id, document.getMetadata().get("kbId"));
                ordinals.add((Integer) document.getMetadata().get("ordinal"));
                vectorIds.add(document.getId());
            }
            return null;
        }).when(vectors).add(anyList());
        CountDownLatch ready = new CountDownLatch(1);
        doAnswer(invocation -> {
            ready.countDown();
            return null;
        }).when(store).ready(eq(id), eq("gen"), eq(23), anyString(), eq(3));
        var service = new KnowledgeService(store, parsers, vectors, new ReadingProperties(), texts, mock(BookSearch.class));
        try {
            service.submit(id, false);
            assertTrue(ready.await(3, TimeUnit.SECONDS), "All chunks should finish embedding");
            assertEquals(List.of(10, 10, 3), batchSizes);
            assertEquals(java.util.stream.IntStream.range(0, 23).boxed().toList(), ordinals);
            var order = inOrder(texts, store);
            order.verify(texts).saveText(id, "gen", ReadingChunker.fullText(sections), ReadingChunker.split(sections, 1200, 200), vectorIds);
            order.verify(store).ready(eq(id), eq("gen"), eq(23), anyString(), eq(3));
            verify(store, never()).status(eq(id), eq("FAILED"), anyString());
        } finally {
            service.close();
        }
    }

    @Test
    void failedFullTextSaveNeverPublishesReadyAndCleansVectors() throws Exception {
        var store = mock(ReadingStore.class);
        var parsers = mock(DocumentParsers.class);
        var vectors = mock(PgVectorStore.class);
        var texts = mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class);
        Path file = directory.resolve("save-failure.epub");
        when(store.knowledgeBase("book")).thenReturn(knowledgeBase("book", file));
        when(store.vectorDimensions("book")).thenReturn(3);
        when(parsers.parseDocument(file, "epub")).thenReturn(new DocumentParsers.ParsedDocument(List.of("原文证据"), Map.of()));
        doThrow(new IllegalStateException("正文存储失败")).when(texts).saveText(anyString(), anyString(), anyString(), anyList(), anyList());
        CountDownLatch failed = new CountDownLatch(1);
        doAnswer(i -> { failed.countDown(); return null; }).when(store).status("book", "FAILED", "正文存储失败");
        var service = new KnowledgeService(store, parsers, vectors, new ReadingProperties(), texts, mock(BookSearch.class));
        try {
            service.submit("book", false);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            verify(store, never()).ready(anyString(), anyString(), anyInt(), anyString(), anyInt());
            verify(store).deleteVectors("book");
        } finally {
            service.close();
        }
    }

    @Test
    void failedEmbeddingsAreCleanedAndCanBeRetried() throws Exception {
        var store = mock(ReadingStore.class);
        String id = UUID.randomUUID().toString();
        Path file = directory.resolve("book.epub");
        Files.writeString(file, "原文");
        doReturn(knowledgeBase(id, file)).when(store).knowledgeBase(id);
        DocumentParsers parsers = mock(DocumentParsers.class);
        when(parsers.parseDocument(file, "epub")).thenReturn(new DocumentParsers.ParsedDocument(List.of("原文证据"), Map.of()));
        PgVectorStore vectors = mock(PgVectorStore.class);
        doThrow(new IllegalStateException("向量服务超时")).doNothing().when(vectors).add(anyList());
        CountDownLatch failed = new CountDownLatch(1), ready = new CountDownLatch(1);
        doAnswer(i -> {
            failed.countDown();
            return null;
        }).when(store).status(eq(id), eq("FAILED"), anyString());
        doAnswer(i -> {
            ready.countDown();
            return null;
        }).when(store).ready(eq(id), eq("gen"), eq(1), anyString(), eq(3));
        doReturn(3).when(store).vectorDimensions(id);
        var p = new ReadingProperties();
        var service = new KnowledgeService(store, parsers, vectors, p, mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class), mock(BookSearch.class));
        try {
            service.submit(id, false);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            verify(store).deleteVectors(id);
            // Wait until worker releases request ownership before explicit retry.
            for (int i = 0; i < 100; i++) {
                try {
                    service.submit(id, true);
                    break;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    Thread.sleep(10);
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            verify(store).resetKb(id);
            verify(vectors, times(2)).add(anyList());
        } finally {
            service.close();
        }
    }

    @Test
    void rebuildingUnsupportedFormatsDoesNotResetExistingKnowledge() {
        var store = mock(ReadingStore.class);
        var vectors = mock(PgVectorStore.class);
        var parsers = mock(DocumentParsers.class);
        var kb = knowledgeBase("legacy", directory.resolve("book.exe"));
        kb.setFormat("exe");
        when(store.knowledgeBase("legacy")).thenReturn(kb);
        var service = new KnowledgeService(store, parsers, vectors, new ReadingProperties(), mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class), mock(BookSearch.class));
        try {
            assertThrows(IllegalArgumentException.class, () -> service.submit("legacy", true));
            verify(store, never()).resetKb(anyString());
            verifyNoInteractions(parsers, vectors);
        } finally {
            service.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pdf", "doc", "docx", "txt", "md"})
    void newlySupportedFormatsCanBeUploadedAndRebuilt(String format) throws Exception {
        var store = mock(ReadingStore.class);
        var parsers = mock(DocumentParsers.class);
        var vectors = mock(PgVectorStore.class);
        var texts = mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class);
        var properties = new ReadingProperties();
        properties.setDataDirectory(directory.toString());
        when(store.knowledgeBase(anyString())).thenAnswer(i -> {
            var kb = knowledgeBase(i.getArgument(0), directory.resolve("book." + format));
            kb.setFormat(format);
            return kb;
        });
        when(store.kb(anyString())).thenAnswer(i -> Map.of("id", i.getArgument(0)));
        when(store.vectorDimensions(anyString())).thenReturn(3);
        when(parsers.parseDocument(any(Path.class), eq(format)))
                .thenReturn(new DocumentParsers.ParsedDocument(List.of("文档正文"), Map.of()));
        var service = new KnowledgeService(store, parsers, vectors, properties, texts, mock(BookSearch.class));
        try {
            var uploaded = service.upload(new MockMultipartFile("file", "book." + format.toUpperCase(Locale.ROOT), "application/octet-stream", new byte[]{1}));
            String id = uploaded.get("id").toString();
            verify(store, timeout(3000)).ready(eq(id), eq("gen"), eq(1), anyString(), eq(3));
            verify(store).addKb(eq(id), eq("book." + format.toUpperCase(Locale.ROOT)), eq(format), anyString());
            assertTrue(Files.exists(directory.resolve(id + "." + format)));
            // A separate existing KB verifies rebuild eligibility without racing upload ownership.
            service.submit("existing", true);
            verify(store, timeout(3000)).ready(eq("existing"), eq("gen"), eq(1), anyString(), eq(3));
            verify(store).resetKb("existing");
        } finally {
            service.close();
        }
    }

    @Test
    void successfulIngestionSurvivesIndexCleanupFailureAndDeletionStillCleansCache() throws Exception {
        var store = mock(ReadingStore.class);
        var parsers = mock(DocumentParsers.class);
        var vectors = mock(PgVectorStore.class);
        var texts = mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class);
        var search = mock(BookSearch.class);
        Path file = Files.writeString(directory.resolve("book.epub"), "fixture");
        when(store.knowledgeBase("book")).thenReturn(knowledgeBase("book", file));
        when(store.vectorDimensions("book")).thenReturn(3);
        when(parsers.parseDocument(file, "epub")).thenReturn(new DocumentParsers.ParsedDocument(List.of("正文证据"), Map.of()));
        doThrow(new java.io.IOException("disk busy")).when(search).discardOldIndexes("book", "gen");
        var service = new KnowledgeService(store, parsers, vectors, new ReadingProperties(), texts, search);
        try {
            service.submit("book", false);
            verify(search, timeout(3000)).discardOldIndexes("book", "gen");
            var order = inOrder(store, search);
            order.verify(store).ready(eq("book"), eq("gen"), eq(1), anyString(), eq(3));
            order.verify(search).discardOldIndexes("book", "gen");
            verify(store, never()).status(eq("book"), eq("FAILED"), anyString());
            for (int i = 0; ; i++) {
                try { service.delete("book"); break; }
                catch (org.springframework.web.server.ResponseStatusException e) {
                    if (i == 100) throw e;
                    Thread.sleep(10);
                }
            }
            order.verify(store).deleteKb("book");
            order.verify(search).deleteIndexes("book");
            assertFalse(Files.exists(file));
        } finally {
            service.close();
        }
    }

    @Test
    void uploadRejectsUnsupportedEmptyAndOversizeFilesWithoutCallingModels() throws Exception {
        var store = mock(ReadingStore.class);
        var vectors = mock(PgVectorStore.class);
        var p = new ReadingProperties();
        p.setDataDirectory(directory.toString());
        var service = new KnowledgeService(store, new DocumentParsers(), vectors, p, mock(com.yupi.yuaiagent.reading.persistence.ReadingBookStore.class), mock(BookSearch.class));
        try {
            assertThrows(IllegalArgumentException.class, () -> service.upload(new MockMultipartFile("file", "book.exe", "application/octet-stream", new byte[]{1})));
            assertThrows(IllegalArgumentException.class, () -> service.upload(new MockMultipartFile("file", "book.epub", "text/plain", new byte[0])));
            assertThrows(IllegalArgumentException.class, () -> service.upload(new MockMultipartFile("file", "book.epub", "application/epub+zip", new byte[20 * 1024 * 1024 + 1])));

            verifyNoInteractions(store, vectors);
        } finally {
            service.close();
        }
    }
}
