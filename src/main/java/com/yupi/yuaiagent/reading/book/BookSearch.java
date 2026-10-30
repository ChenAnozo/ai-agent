package com.yupi.yuaiagent.reading.book;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.persistence.mapper.ReadingBookChunkEntity;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static com.yupi.yuaiagent.reading.book.BookTexts.Book;

/**
 * Lucene is a rebuildable bounded cache; PostgreSQL owns every passage and generation.
 */
@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class BookSearch {
    public record Hit(String passageId, int start, int end, int lexicalRank, int vectorRank,
                      double score) {
    }

    private record Index(Directory directory, DirectoryReader reader, IndexSearcher searcher) implements AutoCloseable {
        public void close() throws Exception {
            try { reader.close(); }
            finally { directory.close(); }
        }
    }

    private final ReadingStore store;
    private final EmbeddingModel embedding;
    private final ReadingProperties p;
    private static final String INDEX_VERSION = "v1";
    private final Path indexRoot;
    private final LinkedHashMap<String, Index> cache = new LinkedHashMap<>(16, .75f, true);

    public BookSearch(ReadingStore store, EmbeddingModel embedding, ReadingProperties p) {
        this.store = store;
        this.embedding = embedding;
        this.p = p;
        this.indexRoot = Path.of(p.getDataDirectory()).toAbsolutePath().normalize().resolve("lucene");
    }

    private Path bookDirectory(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("无效知识库索引标识");
        return indexRoot.resolve(id);
    }

    private Path indexDirectory(Book b) {
        if (b.generation() == null || !b.generation().matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("无效知识库索引版本");
        return bookDirectory(b.id()).resolve(b.generation()).resolve(INDEX_VERSION);
    }

    private Map<String, String> identity(Book b) {
        return Map.of("bookId", b.id(), "generation", b.generation(), "indexVersion", INDEX_VERSION,
                "chunks", Integer.toString(b.chunks().size()));
    }

    private DirectoryReader openValid(Directory directory, Book b) throws IOException {
        DirectoryReader reader;
        try {
            reader = DirectoryReader.open(directory);
        } catch (IndexNotFoundException | CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException
                 | NoSuchFileException | java.io.EOFException e) {
            return null;
        }
        if (reader.numDocs() == b.chunks().size() && reader.getIndexCommit().getUserData().equals(identity(b)))
            return reader;
        reader.close();
        return null;
    }

    private synchronized Index index(Book b) throws Exception {
        String key = b.id() + ":" + b.generation();
        Index existing = cache.get(key);
        if (existing != null) return existing;
        Path path = indexDirectory(b);
        Files.createDirectories(path);
        Directory dir = FSDirectory.open(path);
        DirectoryReader reader;
        try {
            reader = openValid(dir, b);
            if (reader == null) {
                dir.close();
                deleteTree(path);
                Files.createDirectories(path);
                dir = FSDirectory.open(path);
                build(dir, b);
                reader = DirectoryReader.open(dir);
            }
        } catch (Exception e) {
            dir.close();
            throw e;
        }
        var searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        var created = new Index(dir, reader, searcher);
        cache.put(key, created);
        while (cache.size() > 8) {
            var first = cache.entrySet().iterator().next();
            cache.remove(first.getKey());
            first.getValue().close();
        }
        return created;
    }

    private void build(Directory dir, Book b) throws IOException {
        try (var analyzer = new CJKAnalyzer(); var writer = new IndexWriter(dir, new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE).setCommitOnClose(false).setSimilarity(new BM25Similarity()))) {
            for (var row : b.chunks()) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("索引构建已取消");
                int start = row.getStartPos(), end = row.getEndPos();
                var doc = new org.apache.lucene.document.Document();
                doc.add(new StringField("id", row.getVectorId(), Field.Store.YES));
                doc.add(new TextField("text", b.text().substring(start, end), Field.Store.NO));
                writer.addDocument(doc);
            }
            writer.setLiveCommitData(identity(b).entrySet());
            writer.commit();
        }
    }

    /** Close readers before deleting files, including on Windows. Never follow links outside the cache. */
    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Path root = indexRoot.toRealPath();
        Path target = path.toRealPath();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("索引清理路径越界");
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }

    public synchronized void deleteIndexes(String id) throws Exception {
        Path path = bookDirectory(id);
        evict(id, null);
        deleteTree(path);
    }

    public synchronized void discardOldIndexes(String id, String generation) throws Exception {
        var current = store.knowledgeBase(id);
        if (!"READY".equals(current.getStatus()) || !generation.equals(current.getGeneration())) return;
        evict(id, generation);
        Path book = bookDirectory(id);
        if (!Files.isDirectory(book)) return;
        try (var paths = Files.list(book)) {
            for (Path path : paths.toList())
                if (!path.getFileName().toString().equals(generation)) deleteTree(path);
        }
    }

    private void evict(String id, String keepGeneration) throws Exception {
        var entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getKey().startsWith(id + ":") && !entry.getKey().equals(id + ":" + keepGeneration)) {
                entries.remove();
                entry.getValue().close();
            }
        }
    }

    private Map<String, Integer> lexicalSearch(Book book, Query query) throws Exception {
        for (int attempt = 0; ; attempt++) {
            Index index = index(book);
            try {
                Map<String, Integer> ranks = new LinkedHashMap<>();
                var hits = index.searcher().search(query, p.getHybridCandidates());
                int rank = 0;
                for (var hit : hits.scoreDocs)
                    ranks.put(index.reader().storedFields().document(hit.doc).get("id"), ++rank);
                return ranks;
            } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException
                     | NoSuchFileException | java.io.EOFException e) {
                var removed = cache.remove(book.id() + ":" + book.generation());
                if (removed != null) removed.close();
                if (attempt > 0) throw e;
                deleteTree(indexDirectory(book));
            }
        }
    }

    public synchronized List<Hit> search(Book b, String query) throws Exception {
        return search(b, query, p.getTopK(), false);
    }

    public synchronized List<Hit> search(Book b, String query, int topK, boolean passageQuery) throws Exception {
        return search(b, query, query, topK, passageQuery);
    }

    public synchronized List<Hit> search(Book b, String keywordQuery, String semanticQuery, int topK, boolean passageQuery) throws Exception {
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("混合检索返回数量必须在1到100之间");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("检索已取消");
        ReadingStore.required(keywordQuery, passageQuery ? 20000 : 2000);
        ReadingStore.required(semanticQuery, passageQuery ? 20000 : 2000);
        var kb = store.knowledgeBase(b.id());
        if (!"READY".equals(kb.getStatus()) || !b.generation().equals(kb.getGeneration()))
            throw new IllegalArgumentException("知识库版本已变化，请重新加载");
        if (!p.getEmbeddingModelId().equals(kb.getModelId()))
            throw new IllegalArgumentException("向量模型不匹配，请重建知识库");
        var lexical = new BooleanQuery.Builder();
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        try (var analyzer = new CJKAnalyzer(); var stream = analyzer.tokenStream("text", keywordQuery)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) frequencies.merge(term.toString(), 1, Integer::sum);
            stream.end();
        }
        if (!passageQuery && frequencies.size() > 128)
            throw new IllegalArgumentException("检索词过长，请拆成具体子问题");
        // Bound Lucene clauses; embedding receives the complete semantic query independently.
        var tokens = frequencies.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(128).map(Map.Entry::getKey).toList();
        for (String token : tokens) lexical.add(new TermQuery(new Term("text", token)), BooleanClause.Occur.SHOULD);
        Map<String, Integer> bm25 = new LinkedHashMap<>(), vector = new LinkedHashMap<>();
        if (!tokens.isEmpty()) {
            Query q = lexical.build();
            bm25.putAll(lexicalSearch(b, q));
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("检索已取消");
        float[] values = embedding.embed(semanticQuery);
        if (!p.getEmbeddingModelId().equals(kb.getModelId()) || values.length != kb.getDimensions())
            throw new IllegalArgumentException("向量模型不匹配，请重建知识库");
        var vectorIds = store.fullTextVectorIds(b.id(), b.generation(), values, p.getHybridCandidates());
        int rank = 0;
        for (String id : vectorIds) vector.put(id, ++rank);
        Map<String, ReadingBookChunkEntity> chunks = new HashMap<>();
        b.chunks().forEach(c -> chunks.put(c.getVectorId(), c));
        Set<String> all = new LinkedHashSet<>(bm25.keySet());
        all.addAll(vector.keySet());
        return all.stream().map(id -> {
            var c = chunks.get(id);
            int from = c.getStartPos(), to = c.getEndPos();
            int l = bm25.getOrDefault(id, 0), v = vector.getOrDefault(id, 0);
            return new Hit(id, from, to, l, v, rrf(l, v));
        }).sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(Hit::start)).limit(topK).toList();
    }

    public static double rrf(int lexical, int vector) {
        return (lexical == 0 ? 0 : 1.0 / (60 + lexical)) + (vector == 0 ? 0 : 1.0 / (60 + vector));
    }

    @PreDestroy
    public synchronized void close() {
        for (var i : cache.values())
            try {
                i.close();
            } catch (Exception ignored) {
            }
        cache.clear();
    }
}
