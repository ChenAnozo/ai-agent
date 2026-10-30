package com.yupi.yuaiagent.reading.knowledge;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.persistence.ReadingBookStore;
import com.yupi.yuaiagent.reading.book.BookSearch;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class KnowledgeService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeService.class);
    // DashScope limits an embedding request to at most 10 input texts.
    private static final int EMBEDDING_BATCH_SIZE = 10;
    private final ReadingStore store;
    private final DocumentParsers parsers;
    private final PgVectorStore vectors;
    private final ReadingBookStore texts;
    private final ReadingProperties p;
    private final BookSearch search;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20));

    public KnowledgeService(ReadingStore store, DocumentParsers parsers, PgVectorStore readingVectors, ReadingProperties p, ReadingBookStore texts, BookSearch search) {
        this.search = search;
        this.texts = texts;
        this.store = store;
        this.parsers = parsers;
        this.vectors = readingVectors;
        this.p = p;
    }

    public synchronized Map<String, Object> upload(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) filename = "";
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        filename = ReadingStore.required(filename, 250);
        String format = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        DocumentParsers.requireSupported(format);
        if (file.isEmpty() || file.getSize() > 20 * 1024 * 1024L)
            throw new IllegalArgumentException("文件不能为空或超过 20 MB");
        String id = UUID.randomUUID().toString();
        Path dir = Path.of(p.getDataDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(id + "." + format);
        file.transferTo(target);
        try {
            store.addKb(id, filename, format, target.toString());
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw e;
        }
        submit(id, false);
        return store.kb(id);
    }

    public synchronized void submit(String id, boolean rebuild) {
        var existing = store.knowledgeBase(id);
        DocumentParsers.requireSupported(existing.getFormat());
        if (!active.add(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "文件正在处理");
        try {
            if (rebuild) store.resetKb(id);
            executor.execute(() -> ingest(id));
        } catch (Exception e) {
            active.remove(id);
            store.status(id, "FAILED", "任务队列已满，请稍后重试");
            throw e;
        }
    }

    private void ingest(String id) {
        try {
            var kb = store.knowledgeBase(id);
            store.status(id, "PARSING", "");
            var parsed = parsers.parseDocument(Path.of(kb.getPath()), kb.getFormat());
            var sections = parsed.sections();
            String text = ReadingChunker.fullText(sections);
            var chunks = ReadingChunker.split(sections, p.getChunkSize(), p.getChunkOverlap());
            store.status(id, "EMBEDDING", "");
            // Small batches keep embedding requests bounded; READY is published only after every batch succeeds.
            List<Document> documents = chunks.stream().map(c -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                parsed.metadata().forEach((name, values) -> metadata.put("document." + name, values));
                metadata.putAll(Map.of("kbId", id, "generation", kb.getGeneration(), "location", c.location(), "ordinal", c.ordinal(), "start", c.start(), "end", c.end()));
                return new Document(c.text(), metadata);
            }).toList();
            for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH_SIZE)
                vectors.add(documents.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, documents.size())));
            Integer dimensions = store.vectorDimensions(id);
            if (dimensions == null) throw new IllegalStateException("向量生成结果为空");
            texts.saveText(id, kb.getGeneration(), text, chunks, documents.stream().map(Document::getId).toList());
            store.ready(id, kb.getGeneration(), chunks.size(), p.getEmbeddingModelId(), dimensions);
            cleanupIndexes(id, kb.getGeneration());
        } catch (Exception e) {
            store.deleteVectors(id);
            store.status(id, "FAILED", Objects.toString(e.getMessage(), "解析或向量化失败"));
        } finally {
            active.remove(id);
        }
    }

    public synchronized void delete(String id) throws Exception {
        if (active.contains(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "文件正在处理，请等待处理结束");
        var kb = store.knowledgeBase(id);
        Path file = Path.of(kb.getPath());
        Files.deleteIfExists(file);
        store.deleteKb(id);
        cleanupIndexes(id, null);
    }

    // The persisted index is a cache: cleanup failures must not invalidate successful ingestion/deletion.
    private void cleanupIndexes(String id, String generation) {
        try {
            if (generation == null) search.deleteIndexes(id);
            else search.discardOldIndexes(id, generation);
        } catch (Exception e) {
            log.warn("Knowledge base {}: Lucene cache cleanup failed", id, e);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
