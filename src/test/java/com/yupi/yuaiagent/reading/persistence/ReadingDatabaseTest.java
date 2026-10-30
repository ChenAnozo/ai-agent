package com.yupi.yuaiagent.reading.persistence;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.knowledge.ReadingRetrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

/**
 * Opt-in native PostgreSQL test; all application tables are confined to a unique test schema.
 */
@EnabledIfEnvironmentVariable(named = "READING_TEST_DATABASE_URL", matches = ".+")
class ReadingDatabaseTest {
    ReadingRepositoryFixture repositories;
    JdbcTemplate jdbc;
    ReadingStore store;
    ReadingRetrieval retrieval;
    PgVectorStore vectors;
    String schema;
    String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), oldModel = UUID.randomUUID().toString();

    @BeforeEach
    void setup() {
        schema = "reading_test_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("READING_TEST_DATABASE_URL"));
        ds.setUser(System.getenv("READING_TEST_DATABASE_USER"));
        ds.setPassword(System.getenv("READING_TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + schema);
        ds.setCurrentSchema(schema + ",public");
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/reading").load().migrate();
        repositories = new ReadingRepositoryFixture(jdbc);
        store = repositories.store;
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.dimensions()).thenReturn(3);
        when(model.embed(anyString())).thenReturn(new float[]{1, 0, 0});
        when(model.embed(anyList(), any(EmbeddingOptions.class), any(org.springframework.ai.embedding.BatchingStrategy.class))).thenAnswer(i -> ((List<?>) i.getArgument(0)).stream().map(d -> new float[]{1, 0, 0}).toList());
        vectors = PgVectorStore.builder(jdbc, model).schemaName(schema).vectorTableName("reading_vectors").initializeSchema(false).indexType(PgVectorStore.PgIndexType.NONE).build();
        var p = new ReadingProperties();
        retrieval = new ReadingRetrieval(store, p, model);
        for (String id : List.of(first, second, oldModel)) store.addKb(id, "书籍 " + id, "md", "unused");
        for (String id : List.of(first, second)) {
            vectors.add(List.of(new Document("书籍原文 " + id, Map.of("kbId", id, "generation", store.kb(id).get("generation"), "location", "第一章"))));
            store.status(id, "EMBEDDING", "");
            store.ready(id, store.knowledgeBase(id).getGeneration(), 1, p.getEmbeddingModelId(), 3);
        }
        // Deliberately incompatible, unrelated vectors must not be evaluated by the distance operator.
        jdbc.update("INSERT INTO reading_vectors VALUES(?::uuid,'old',?::jsonb,'[1,0]'::vector)", UUID.randomUUID().toString(), store.encode(Map.of("kbId", oldModel, "generation", store.kb(oldModel).get("generation"))));
        store.status(oldModel, "EMBEDDING", "");
        store.ready(oldModel, store.knowledgeBase(oldModel).getGeneration(), 1, "old/model", 2);
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null && schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void selectedKnowledgeBasesAreIsolatedBeforeDistanceEvaluation() {
        assertEquals(1, retrieval.search(List.of(first), "原文").size());
        assertEquals(2, retrieval.search(List.of(first, second), "原文").size());
        assertThrows(IllegalArgumentException.class, () -> retrieval.search(List.of(oldModel), "原文"));
        store.resetKb(first);
        assertThrows(IllegalArgumentException.class, () -> retrieval.search(List.of(first), "原文"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_vectors WHERE metadata->>'kbId'=?", Integer.class, first));
    }

    @Test
    void sessionsAndEvidenceSurviveNewRepositoryInstances() {
        var session = store.createSession();
        String id = (String) session.get("id");
        var settings = new Settings(List.of(first), "", false);
        store.settings(id, settings);
        String run = UUID.randomUUID().toString();
        store.startRun(run, id, "问题");
        var sources = List.of(new Source("S1", "BOOK", "书籍", "第一章", "", "不可变的原文快照"));
        store.checkpoint(run, "回答[S1]", "COMPLETED", sources, List.of(new Event("tool_call", Map.of("query", "问题"))));
        store.deleteKb(first);
        store.settings(id, new Settings(List.of(second), "", false));
        var reopened = new ReadingRepositoryFixture(jdbc).store;
        var history = reopened.history(id);
        assertEquals("回答[S1]", history.getFirst().get("answer"));
        assertTrue(reopened.encode(history.getFirst().get("sources")).contains("不可变的原文快照"));
        assertFalse(history.getFirst().containsKey("snapshot"));
        assertEquals(List.of(second), reopened.settings(id).knowledgeBaseIds());
    }

    @Test
    void mapperJsonbCompatibilityIsolationAndCascade() {
        String session = (String) store.createSession().get("id"), other = (String) store.createSession().get("id");
        jdbc.update("UPDATE reading_session SET settings='{\"retrievalMode\":\"BROAD\",\"interestSelectionMode\":\"MANUAL\"}'::jsonb WHERE id=?", session);
        assertEquals("MANUAL", store.settings(session).interestSelectionMode());
        var settings = new Settings(List.of(first), "collaborative-research", "", "MANUAL");
        store.settings(session, settings);
        assertEquals(settings, store.settings(session));
        assertFalse(jdbc.queryForObject("SELECT jsonb_exists(settings, 'retrievalMode') FROM reading_session WHERE id=?", Boolean.class, session));
        String run = UUID.randomUUID().toString();
        store.startRun(run, session, "第一个问题");
        store.startRun(UUID.randomUUID().toString(), session, "第二个问题");
        assertEquals("第一个问题", store.session(session).get("title"));
        store.checkpoint(run, "", "RUNNING", List.of(new Source("S1", "BOOK", "标题", "字符0–3", "", "原文\n内容")), List.of(new Event("tool_result", Map.of("count", 2, "nested", Map.of("items", List.of(1, 2))))));
        var history = store.history(session);
        assertEquals(2, history.size());
        assertTrue(store.history(other).isEmpty());
        var row = history.stream().filter(r -> run.equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals("", row.get("answer"));
        assertFalse(row.containsKey("snapshot"));
        assertEquals("{}", jdbc.queryForObject("SELECT snapshot::text FROM reading_run WHERE id=?", String.class, run));
        assertTrue(store.encode(row.get("events")).contains("nested"));
        assertInstanceOf(String.class, row.get("createdAt"));
        store.checkpoint(run, "完成", "COMPLETED", List.of(), List.of());
        row = store.history(session).stream().filter(r -> run.equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals("[]", store.encode(row.get("events")));
        assertEquals("[]", store.encode(row.get("sources")));
        store.saveHistorySummary(session, "摘要", run);
        store.deleteSession(session);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_run WHERE session_id=?", Integer.class, session));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_history_summary WHERE session_id=?", Integer.class, session));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.session(session));
        assertEquals(other, store.session(other).get("id"));
    }

    @Test
    void mapperInsertAndSessionUpdateRollbackTogether() {
        String session = (String) store.createSession().get("id"), run = UUID.randomUUID().toString();
        var template = repositories.template;
        var sessionMapper = mock(com.yupi.yuaiagent.reading.persistence.mapper.ReadingSessionMapper.class, org.mockito.AdditionalAnswers.delegatesTo(template.getMapper(com.yupi.yuaiagent.reading.persistence.mapper.ReadingSessionMapper.class)));
        doThrow(new IllegalStateException("模拟标题更新失败")).when(sessionMapper).touchForRun(anyString(), anyString());
        var failingStore = new ReadingStore(new ObjectMapper(), new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), sessionMapper, template.getMapper(com.yupi.yuaiagent.reading.persistence.mapper.ReadingRunMapper.class), template.getMapper(com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbMapper.class), template.getMapper(com.yupi.yuaiagent.reading.persistence.mapper.ReadingHistorySummaryMapper.class), template.getMapper(com.yupi.yuaiagent.reading.persistence.mapper.ReadingVectorMapper.class));
        assertThrows(IllegalStateException.class, () -> failingStore.startRun(run, session, "问题"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_run WHERE id=?", Integer.class, run));
        assertEquals("新对话", store.session(session).get("title"));
    }

    @Test
    void springWiresMappersToTheSameIsolatedDataSource() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.yupi.yuaiagent.reading.config.ReadingMapperConfiguration.class, ReadingStore.class, ReadingBookStore.class)
                .withPropertyValues("reading.enabled=true")
                .withBean("readingJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("readingTransactions", TransactionTemplate.class, () -> new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var wired = context.getBean(ReadingStore.class);
                    String id = (String) wired.createSession().get("id");
                    wired.startRun(UUID.randomUUID().toString(), id, "Spring接线验证");
                    assertEquals(1, wired.history(id).size());
                    var books = context.getBean(ReadingBookStore.class);
                    String generation = (String) wired.kb(first).get("generation");
                    store.status(first, "EMBEDDING", "");
                    books.saveText(first, generation, "接线原文",
                            List.of(new com.yupi.yuaiagent.reading.api.ReadingTypes.Chunk("接线原文", 0, 4, 0)), List.of(UUID.randomUUID().toString()));
                    assertEquals("接线原文", books.document(first, generation).getTextContent());
                });
    }

    @Test
    void knowledgeMetadataDefaultsAndRebuildStayCompatible() {
        String id = UUID.randomUUID().toString();
        store.addKb(id, "中文书籍.md", "md", "unused");
        var original = store.kb(id);
        assertEquals("PENDING", original.get("status"));
        assertEquals("", original.get("error"));
        assertEquals(0, original.get("chunkCount"));
        assertNull(original.get("modelId"));
        assertNull(original.get("dimensions"));
        assertInstanceOf(String.class, original.get("createdAt"));
        store.renameKb(id, " 新名称 ");
        assertEquals("新名称", store.kb(id).get("name"));
        assertEquals("中文书籍.md", store.kb(id).get("filename"));
        store.status(id, "FAILED", "中文错误\n详情");
        assertEquals("中文错误\n详情", store.kb(id).get("error"));
        String generation = (String) original.get("generation");
        assertThrows(IllegalArgumentException.class, () -> store.ready(id, generation, 8, "embedding/test", 3));
        store.status(id, "EMBEDDING", "");
        store.ready(id, generation, 8, "embedding/test", 3);
        assertEquals(8, store.kb(id).get("chunkCount"));
        store.resetKb(id);
        var reset = store.kb(id);
        assertNotEquals(original.get("generation"), reset.get("generation"));
        assertEquals("PENDING", reset.get("status"));
        assertEquals(0, reset.get("chunkCount"));
        assertNull(reset.get("modelId"));
        assertNull(reset.get("dimensions"));
        assertEquals(original.get("createdAt"), reset.get("createdAt"));
        assertTrue(store.knowledgeBases().stream().anyMatch(row -> id.equals(row.get("id"))));
        store.deleteKb(id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.kb(id));
        assertNotNull(store.kb(second));
    }

    @Test
    void summariesRemainIsolatedAndCascadeWithSessionDeletion() {
        String session = (String) store.createSession().get("id"), other = (String) store.createSession().get("id");
        assertTrue(store.historySummary(session).isEmpty());
        store.saveHistorySummaryProgress(session, "摘要\n一", "run1", "run2", 42);
        assertEquals(Map.of("summary", "摘要\n一", "coveredRunId", "run1", "pendingRunId", "run2", "pendingOffset", 42), store.historySummary(session));
        assertTrue(store.historySummary(other).isEmpty());
        store.saveHistorySummary(session, "覆盖后的摘要", "run2");
        assertEquals(Map.of("summary", "覆盖后的摘要", "coveredRunId", "run2", "pendingRunId", "", "pendingOffset", 0), store.historySummary(session));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reading_history_summary WHERE session_id=?", Integer.class, session));
        store.deleteSession(session);
        assertTrue(store.historySummary(session).isEmpty());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> store.saveHistorySummary(session, "失效会话", "run2"));
    }

    @Test
    void vectorCleanupFailureRollsBackKnowledgeRebuildAndDelete() {
        var failingStore = spy(store);
        doThrow(new IllegalStateException("vector cleanup failure")).when(failingStore).deleteVectors(first);
        var original = store.kb(first);
        assertThrows(IllegalStateException.class, () -> failingStore.resetKb(first));
        assertEquals(original, store.kb(first));
        assertThrows(IllegalStateException.class, () -> failingStore.deleteKb(first));
        assertEquals(original, store.kb(first));
        assertEquals(1, retrieval.search(List.of(first), "原文").size());
    }

    @Test
    void disabledReadingDoesNotCreateMappersOrRequireDataSource() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.yupi.yuaiagent.reading.config.ReadingMapperConfiguration.class, ReadingStore.class, ReadingBookStore.class)
                .withPropertyValues("reading.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(org.apache.ibatis.session.SqlSessionFactory.class).isEmpty());
                    assertTrue(context.getBeansOfType(ReadingStore.class).isEmpty());
                    assertTrue(context.getBeansOfType(ReadingBookStore.class).isEmpty());
                });
    }

    @Test
    void vectorMapperPreservesSourceFieldsAndRejectsStaleGenerations() {
        String location = "中文章节 '引号'\n第二行";
        jdbc.update("UPDATE reading_vectors SET metadata=jsonb_set(metadata,'{location}',?::jsonb) WHERE metadata->>'kbId'=?", store.encode(location), first);
        jdbc.update("INSERT INTO reading_vectors VALUES(?::uuid,'过期片段',?::jsonb,'[1,0]'::vector)", UUID.randomUUID().toString(), store.encode(Map.of("kbId", first, "generation", "stale")));
        var source = retrieval.search(List.of(first), "带引号的检索 ' 中文").getFirst();
        assertEquals("BOOK", source.type());
        assertEquals(location, source.location());
        assertEquals("书籍 " + first, source.title());
        assertEquals("书籍原文 " + first, source.text());
        assertEquals("", source.url());
        jdbc.update("UPDATE reading_vectors SET metadata=metadata-'location' WHERE metadata->>'kbId'=?", first);
        assertEquals("正文", retrieval.search(List.of(first), "正文").getFirst().location());
        assertTrue(store.vectorSearch(List.of(), "unused", 3, new float[]{1, 0, 0}, 3).isEmpty());
        assertNull(store.vectorDimensions("missing"));
        store.deleteVectors(first);
        assertTrue(retrieval.search(List.of(first), "正文").isEmpty());
        assertEquals(1, retrieval.search(List.of(second), "正文").size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RUNNING", "WAITING_FOR_SELECTION"})
    void startupRecoveryRunsBeforeReadingServicesAndIsIdempotent(String initialStatus) {
        String pending = UUID.randomUUID().toString();
        store.addKb(pending, "待处理", "md", "unused");
        String session = (String) store.createSession().get("id"), run = UUID.randomUUID().toString(), completed = UUID.randomUUID().toString();
        store.startRun(run, session, "未完成问题");
        store.checkpoint(run, "部分正文", initialStatus, List.of(), List.of(new Event("interest_candidates", Map.of("selectionId", "saved-choice", "round", 1, "candidates", List.of()))));
        store.startRun(completed, session, "完成的问题");
        store.checkpoint(completed, "完整正文", "COMPLETED", List.of(), List.of());
        var books = repositories.books;
        String generation = (String) store.kb(first).get("generation");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.yupi.yuaiagent.reading.config.ReadingMapperConfiguration.class, ReadingStore.class, ReadingBookStore.class)
                .withPropertyValues("reading.enabled=true").withBean("readingJdbc", JdbcTemplate.class, () -> jdbc)
                .withBean(ObjectMapper.class, ObjectMapper::new).withBean("readingTransactions", TransactionTemplate.class, () -> new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var wired = context.getBean(ReadingStore.class);
                    assertEquals("FAILED", wired.kb(pending).get("status"));
                    assertEquals("READY", wired.kb(first).get("status"));
                    var history = wired.history(session);
                    var interrupted = history.stream().filter(r -> run.equals(r.get("id"))).findFirst().orElseThrow();
                    assertEquals("INTERRUPTED", interrupted.get("status"));
                    assertTrue(interrupted.get("events").toString().contains("saved-choice"));
                    assertEquals("部分正文\n服务中断，本次回答未完成。", interrupted.get("answer"));
                    assertEquals("完整正文", history.stream().filter(r -> completed.equals(r.get("id"))).findFirst().orElseThrow().get("answer"));
                    context.getBean(ReadingStartupRecovery.class).recover();
                    assertEquals(history, wired.history(session));
                });
    }

    @Test
    void failedStartupRecoveryRollsBackAllStateRepairs() {
        String pending = UUID.randomUUID().toString();
        store.addKb(pending, "待处理", "md", "unused");
        String session = (String) store.createSession().get("id"), run = UUID.randomUUID().toString();
        store.startRun(run, session, "问题");
        var template = repositories.template;
        var mapper = mock(com.yupi.yuaiagent.reading.persistence.mapper.ReadingRecoveryMapper.class, org.mockito.AdditionalAnswers.delegatesTo(template.getMapper(com.yupi.yuaiagent.reading.persistence.mapper.ReadingRecoveryMapper.class)));
        doThrow(new IllegalStateException("模拟研究恢复失败")).when(mapper).interruptRuns();
        var recovery = new ReadingStartupRecovery(mapper, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
        assertThrows(IllegalStateException.class, recovery::recover);
        assertEquals("PENDING", store.kb(pending).get("status"));
        assertEquals("RUNNING", store.history(session).getFirst().get("status"));
        assertEquals("", store.history(session).getFirst().get("answer"));
    }
}
