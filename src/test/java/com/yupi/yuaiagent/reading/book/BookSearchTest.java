package com.yupi.yuaiagent.reading.book;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.persistence.mapper.ReadingBookChunkEntity;
import com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookSearchTest {
    @TempDir Path directory;
    ReadingProperties properties = new ReadingProperties();
    ReadingStore store = mock(ReadingStore.class);
    EmbeddingModel embedding = mock(EmbeddingModel.class);
    Map<String, ReadingKbEntity> knowledge = new HashMap<>();
    BookSearch search;

    @BeforeEach void setup() {
        properties.setDataDirectory(directory.toString());
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0});
        when(store.knowledgeBase(anyString())).thenAnswer(i -> knowledge.get(i.getArgument(0)));
        search = new BookSearch(store, embedding, properties);
    }

    @AfterEach void close() { search.close(); }

    private BookTexts.Book book(String id, String generation, String text) {
        var kb = new ReadingKbEntity();
        kb.setId(id);
        kb.setGeneration(generation);
        kb.setStatus("READY");
        kb.setModelId(properties.getEmbeddingModelId());
        kb.setDimensions(2);
        knowledge.put(id, kb);
        var chunk = new ReadingBookChunkEntity();
        chunk.setVectorId("passage-" + id + "-" + generation);
        chunk.setStartPos(0);
        chunk.setEndPos(text.length());
        return new BookTexts.Book(id, id, generation, text, List.of(chunk));
    }

    private Path index(BookTexts.Book book) {
        return directory.resolve("lucene").resolve(book.id()).resolve(book.generation()).resolve("v1");
    }

    private void assertLexicalHit(BookTexts.Book book, String query) throws Exception {
        var hits = search.search(book, query);
        assertEquals(1, hits.size());
        assertTrue(hits.getFirst().lexicalRank() > 0);
        assertEquals(book.chunks().getFirst().getVectorId(), hits.getFirst().passageId());
    }

    @Test void lexicalAndVectorSearchUseIndependentQueryTextsAndKeepFusion() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        when(store.fullTextVectorIds(eq(book.id()), eq(book.generation()), any(float[].class), anyInt()))
                .thenReturn(List.of(book.chunks().getFirst().getVectorId()));
        var hit = search.search(book, "alpha", "a different semantic question", 3, true).getFirst();
        assertEquals(1, hit.lexicalRank());
        assertEquals(1, hit.vectorRank());
        assertEquals(BookSearch.rrf(1, 1), hit.score());
        verify(embedding).embed("a different semantic question");
        verify(embedding, never()).embed("alpha");
        var vectorOnly = search.search(book, "unmatched", "alpha", 3, true).getFirst();
        assertEquals(0, vectorOnly.lexicalRank(), "Semantic text must not enter lexical matching");
        assertEquals(1, vectorOnly.vectorRank());
    }

    @Test void singleQueryEntryStillEmbedsTheSameTextAndValidatesBothInputs() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        assertEquals(1, search.search(book, "alpha", 3, true).getFirst().lexicalRank());
        verify(embedding).embed("alpha");
        assertThrows(IllegalArgumentException.class, () -> search.search(book, "alpha", " ", 3, true));
        assertThrows(IllegalArgumentException.class, () -> search.search(book, " ", "alpha", 3, true));
        verifyNoMoreInteractions(embedding);
    }

    @Test void reopeningReusesCommittedIndexWithoutBuildingAgain() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        assertLexicalHit(book, "alpha");
        Path marker = Files.writeString(index(book).resolve("reuse-marker"), "keep");
        search.close();
        search = new BookSearch(store, embedding, properties);
        assertLexicalHit(book, "alpha");
        assertTrue(Files.exists(marker), "Rebuilding would have removed the marker");
    }

    @Test void readerEvictionKeepsFilesForReuse() throws Exception {
        var first = book("book0", "g1", "alpha evidence");
        assertLexicalHit(first, "alpha");
        Path marker = Files.writeString(index(first).resolve("reuse-marker"), "keep");
        for (int i = 1; i < 10; i++) assertLexicalHit(book("book" + i, "g1", "alpha evidence"), "alpha");
        assertLexicalHit(first, "alpha");
        assertTrue(Files.exists(marker));
    }

    @Test void generationChangeUsesNewTextAndRejectsStaleCleanupAndQueries() throws Exception {
        var previous = book("book", "g1", "alpha evidence");
        assertLexicalHit(previous, "alpha");
        var current = book("book", "g2", "beta evidence");
        assertLexicalHit(current, "beta");
        assertTrue(search.search(current, "alpha").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> search.search(previous, "alpha"));
        search.discardOldIndexes("book", "g1");
        assertTrue(Files.exists(index(current)));
        search.discardOldIndexes("book", "g2");
        assertFalse(Files.exists(index(previous)));
        assertLexicalHit(current, "beta");
    }

    @Test void missingUnfinishedAndCorruptIndexesAreRebuilt() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        Files.createDirectories(index(book));
        Files.writeString(index(book).resolve("unfinished"), "interrupted build");
        assertLexicalHit(book, "alpha");
        assertFalse(Files.exists(index(book).resolve("unfinished")));
        search.close();
        Path segments;
        try (var paths = Files.list(index(book))) {
            segments = paths.filter(p -> p.getFileName().toString().startsWith("segments_")).findFirst().orElseThrow();
        }
        Files.write(segments, new byte[]{1, 2, 3});
        search = new BookSearch(store, embedding, properties);
        assertLexicalHit(book, "alpha");
        search.deleteIndexes(book.id());
        assertFalse(Files.exists(directory.resolve("lucene/book")));
        assertLexicalHit(book, "alpha");
    }

    @Test void mismatchedIndexMetadataTriggersRebuild() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        assertLexicalHit(book, "alpha");
        search.close();
        try (var dir = org.apache.lucene.store.FSDirectory.open(index(book));
             var analyzer = new org.apache.lucene.analysis.cjk.CJKAnalyzer();
             var writer = new org.apache.lucene.index.IndexWriter(dir, new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
            writer.setLiveCommitData(Map.of("indexVersion", "obsolete").entrySet());
        }
        Path marker = Files.writeString(index(book).resolve("old-marker"), "old");
        search = new BookSearch(store, embedding, properties);
        assertLexicalHit(book, "alpha");
        assertFalse(Files.exists(marker));
    }

    @Test void missingSegmentFileIsRebuilt() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        assertLexicalHit(book, "alpha");
        search.close();
        Path segment;
        try (var paths = Files.list(index(book))) {
            segment = paths.filter(p -> p.getFileName().toString().endsWith(".cfs")).findFirst().orElseThrow();
        }
        Files.delete(segment);
        search = new BookSearch(store, embedding, properties);
        assertLexicalHit(book, "alpha");
    }

    @Test void failedBuildDoesNotPublishPartialIndex() throws Exception {
        var book = book("book", "g1", "alpha evidence");
        var invalid = new ReadingBookChunkEntity();
        invalid.setVectorId("invalid");
        invalid.setStartPos(0);
        invalid.setEndPos(book.text().length() + 1);
        var broken = new BookTexts.Book(book.id(), book.name(), book.generation(), book.text(),
                List.of(book.chunks().getFirst(), invalid));
        assertThrows(IndexOutOfBoundsException.class, () -> search.search(broken, "alpha"));
        try (var dir = org.apache.lucene.store.FSDirectory.open(index(book))) {
            assertFalse(org.apache.lucene.index.DirectoryReader.indexExists(dir));
        }
        assertLexicalHit(book, "alpha");
    }

    @Test void deletionClosesReadersAndLeavesOtherBooksAlone() throws Exception {
        var first = book("first", "g1", "alpha evidence");
        var other = book("other", "g1", "beta evidence");
        assertLexicalHit(first, "alpha");
        assertLexicalHit(other, "beta");
        search.deleteIndexes("first");
        assertFalse(Files.exists(index(first)));
        assertLexicalHit(other, "beta");
        assertThrows(IllegalArgumentException.class, () -> search.deleteIndexes("../other"));
        assertTrue(Files.exists(index(other)));
    }
}
