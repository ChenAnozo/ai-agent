package com.yupi.yuaiagent.reading.book;

import com.yupi.yuaiagent.reading.api.ReadingTypes.Chunk;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.knowledge.DocumentParsers;
import com.yupi.yuaiagent.reading.knowledge.ReadingChunker;
import com.yupi.yuaiagent.reading.persistence.ReadingRepositoryFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "READING_TEST_DATABASE_URL", matches = ".+")
class BookTextsDatabaseTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    ReadingRepositoryFixture repositories;
    BookTexts texts;
    BookSearch search;
    ReadingProperties p = new ReadingProperties();
    String schema;

    @BeforeEach void setup() {
        schema = "text_test_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("READING_TEST_DATABASE_URL"));
        ds.setUser(System.getenv("READING_TEST_DATABASE_USER"));
        ds.setPassword(System.getenv("READING_TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + schema);
        ds.setCurrentSchema(schema + ",public");
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/reading").load().migrate();
        repositories = new ReadingRepositoryFixture(jdbc);
        texts = new BookTexts(repositories.store, repositories.books);
        p.setChunkSize(300);
        p.setDataDirectory(directory.toString());
        p.setChunkOverlap(50);
    }

    @AfterEach void cleanup() {
        if (search != null) search.close();
        if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    String ingest(String html) throws Exception {
        String id = UUID.randomUUID().toString();
        Path path = directory.resolve(id + ".epub");
        Files.write(path, com.yupi.yuaiagent.reading.EpubFixture.book(html));
        repositories.store.addKb(id, "原文测试", "epub", path.toString());
        var sections = new DocumentParsers().parse(path, "epub");
        String text = ReadingChunker.fullText(sections);
        var chunks = ReadingChunker.split(sections, p.getChunkSize(), p.getChunkOverlap());
        String gen = repositories.store.knowledgeBase(id).getGeneration();
        List<String> ids = new ArrayList<>();
        for (var c : chunks) {
            String vectorId = UUID.randomUUID().toString();
            ids.add(vectorId);
            jdbc.update("INSERT INTO reading_vectors VALUES(?::uuid,?,?::jsonb,'[1,0,0]'::vector)", vectorId, c.text(),
                    repositories.store.encode(Map.of("kbId", id, "generation", gen, "ordinal", c.ordinal())));
        }
        repositories.store.status(id, "EMBEDDING", "");
        repositories.books.saveText(id, gen, text, chunks, ids);
        repositories.store.ready(id, gen, chunks.size(), p.getEmbeddingModelId(), 3);
        return id;
    }

    @Test void fullTextSupportsHybridSearchImmediatelyAfterIngestion() throws Exception {
        String id = ingest("<p>" + "甲".repeat(222) + "跨界罕见短语".repeat(20) + "</p><p>组织结构制约群众动员。</p><p>参考文献也保留原文。</p>");
        String other = ingest("<p>未选中书籍的独有内容。</p>");
        var book = texts.load(id);
        var embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenReturn(new float[]{1, 0, 0});
        search = new BookSearch(repositories.store, embedding, p);
        var hits = search.search(book, "组织结构 群众动员");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> h.lexicalRank() > 0 && h.vectorRank() > 0));
        for (var hit : hits) {
            assertTrue(book.chunks().stream().anyMatch(c -> c.getVectorId().equals(hit.passageId())));
            assertTrue(hit.start() >= 0 && hit.end() > hit.start() && hit.end() <= book.text().length());
            assertEquals(book.chunks().stream().filter(c -> c.getVectorId().equals(hit.passageId())).findFirst().orElseThrow().getEndPos(), hit.end());
        }
        assertTrue(repositories.store.fullTextVectorIds(other, book.generation(), new float[]{1, 0, 0}, 100).isEmpty());
        assertTrue(repositories.store.fullTextVectorIds(id, "missing", new float[]{1, 0, 0}, 100).isEmpty());
        assertNotNull(texts.load(other));
        repositories.store.resetKb(id);
        assertThrows(IllegalArgumentException.class, () -> texts.load(id));
    }

    @Test void textAndBatchedPositionsRollbackTogether() {
        String id = UUID.randomUUID().toString();
        repositories.store.addKb(id, "事务测试", "epub", "unused");
        String generation = repositories.store.knowledgeBase(id).getGeneration();
        repositories.store.status(id, "EMBEDDING", "");
        String text = "甲".repeat(600);
        List<Chunk> positions = new ArrayList<>();
        List<String> vectors = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            positions.add(new Chunk("甲", i, i + 1, i));
            vectors.add(UUID.randomUUID().toString());
        }
        var bad = new ArrayList<>(vectors);
        bad.set(599, bad.getFirst());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> repositories.books.saveText(id, generation, text, positions, bad));
        assertNull(repositories.books.document(id, generation));
        assertTrue(repositories.books.chunks(id, generation).isEmpty());
        repositories.books.saveText(id, generation, text, positions, vectors);
        assertEquals(600, repositories.books.chunks(id, generation).size());
        repositories.store.resetKb(id);
        assertThrows(IllegalArgumentException.class, () -> repositories.books.saveText(id, generation, text, positions, vectors));
        assertThrows(IllegalArgumentException.class, () -> repositories.store.ready(id, generation, 600, "test", 3));
        assertEquals("PENDING", repositories.store.knowledgeBase(id).getStatus());
        repositories.store.deleteKb(id);
        assertNull(repositories.books.document(id, generation));
    }

    @Test void migrationKeepsUsableLegacyTextAndFlagsVectorOnlyKnowledgeForReingestion() {
        String legacySchema = schema + "_legacy";
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("READING_TEST_DATABASE_URL"));
        ds.setUser(System.getenv("READING_TEST_DATABASE_USER"));
        ds.setPassword(System.getenv("READING_TEST_DATABASE_PASSWORD"));
        jdbc.execute("CREATE SCHEMA " + legacySchema);
        ds.setCurrentSchema(legacySchema + ",public");
        var legacy = new JdbcTemplate(ds);
        try {
            Flyway.configure().dataSource(ds).schemas(legacySchema).defaultSchema(legacySchema)
                    .locations("classpath:db/reading").target("5").load().migrate();
            for (String id : List.of("complete", "vectors-only", "missing-positions"))
                legacy.update("INSERT INTO reading_kb(id,name,filename,format,path,status,chunk_count,generation) VALUES(?,?,?,'epub','unused','READY',1,'g')", id, id, id);
            legacy.update("INSERT INTO reading_book_document(kb_id,generation,text_content,nodes) VALUES('complete','g','原文','[]'),('missing-positions','g','原文','[]')");
            String vectorId = UUID.randomUUID().toString();
            legacy.update("INSERT INTO reading_vectors VALUES(?::uuid,'原文','{\"kbId\":\"complete\",\"generation\":\"g\",\"ordinal\":0}'::jsonb,'[1,0,0]'::vector)", vectorId);
            legacy.update("INSERT INTO reading_book_chunk VALUES('complete','g',?::uuid,0,0,2,'root')", vectorId);
            legacy.update("INSERT INTO reading_book_map(id,kb_id,generation,status,model_id,prompt_version) VALUES('old-map','complete','g','READY','test','test')");
            Flyway.configure().dataSource(ds).schemas(legacySchema).defaultSchema(legacySchema)
                    .locations("classpath:db/reading").load().migrate();
            assertEquals("READY", legacy.queryForObject("SELECT status FROM reading_kb WHERE id='complete'", String.class));
            assertEquals("原文", legacy.queryForObject("SELECT text_content FROM reading_book_document WHERE kb_id='complete'", String.class));
            for (String id : List.of("vectors-only", "missing-positions")) {
                assertEquals("FAILED", legacy.queryForObject("SELECT status FROM reading_kb WHERE id=?", String.class, id));
                assertTrue(legacy.queryForObject("SELECT error FROM reading_kb WHERE id=?", String.class, id).contains("重新入库"));
            }
            assertNull(legacy.queryForObject("SELECT to_regclass(?)::text", String.class, legacySchema + ".reading_book_map"));
        } finally {
            jdbc.execute("DROP SCHEMA " + legacySchema + " CASCADE");
        }
    }
}
