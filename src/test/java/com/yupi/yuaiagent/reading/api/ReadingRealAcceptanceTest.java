package com.yupi.yuaiagent.reading.api;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.knowledge.DocumentParsers;
import com.yupi.yuaiagent.reading.knowledge.KnowledgeService;
import com.yupi.yuaiagent.reading.knowledge.ReadingChunker;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit opt-in for the user-provided acceptance book and real model calls.
 */
@EnabledIfEnvironmentVariable(named = "READING_REAL_ACCEPTANCE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=reading", "spring.ai.mcp.client.enabled=false", "logging.level.org.springframework.ai=INFO"})
class ReadingRealAcceptanceTest {
    @Autowired
    KnowledgeService knowledge;
    @Autowired
    ReadingStore store;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate readingJdbc;
    @Autowired
    ReadingProperties p;
    @Autowired
    DocumentParsers parsers;

    @Test
    void realUploadFinishesAndAcceptanceBookHasCompleteIndex() throws Exception {
        var small = new MockMultipartFile("file", "验收入库测试.epub", "application/epub+zip", com.yupi.yuaiagent.reading.EpubFixture.book("<h1>阅读验收</h1><p>书籍研究需要核对原文证据，不能编造引文。</p>"));
        String testId = (String) knowledge.upload(small).get("id");
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
            Map<String, Object> uploaded;
            do {
                uploaded = store.kb(testId);
                if (Set.of("READY", "FAILED").contains(uploaded.get("status"))) break;
                Thread.sleep(500);
            } while (System.nanoTime() < deadline);
            assertEquals("READY", uploaded.get("status"), Objects.toString(uploaded.get("error")));
        } finally {
            knowledge.delete(testId);
        }
        // Verify the previously imported book without modifying or re-embedding its text.
        String id = Files.readString(Path.of(p.getDataDirectory(), "acceptance-kb-id")).strip();
        var kb = store.kb(id);
        var chunks = ReadingChunker.split(parsers.parse(Path.of((String) kb.get("path")), "epub"), p.getChunkSize(), p.getChunkOverlap());
        String generation = (String) kb.get("generation");
        Integer count = readingJdbc.queryForObject("SELECT count(*) FROM reading_vectors WHERE metadata->>'kbId'=? AND metadata->>'generation'=?", Integer.class, id, generation);
        Integer distinct = readingJdbc.queryForObject("SELECT count(DISTINCT metadata->>'ordinal') FROM reading_vectors WHERE metadata->>'kbId'=? AND metadata->>'generation'=?", Integer.class, id, generation);
        assertEquals(chunks.size(), count);
        assertEquals(chunks.size(), distinct);
        var dimensions = readingJdbc.queryForList("SELECT DISTINCT vector_dims(embedding) FROM reading_vectors WHERE metadata->>'kbId'=?", Integer.class, id);
        assertEquals(1, dimensions.size());
        assertEquals("READY", kb.get("status"));
        assertEquals(p.getEmbeddingModelId(), kb.get("modelId"));
        System.out.println("Verified complete real book index: " + count + " chunks, " + dimensions.getFirst() + " dimensions");
    }
}
