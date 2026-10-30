package com.yupi.yuaiagent.reading.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yupi.yuaiagent.reading.persistence.mapper.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

@Repository
@org.springframework.context.annotation.DependsOn("readingStartupRecovery")
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ReadingStore {
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ReadingSessionMapper sessionMapper;
    private final ReadingRunMapper runMapper;
    private final ReadingKbMapper kbMapper;
    private final ReadingHistorySummaryMapper summaryMapper;
    private final ReadingVectorMapper vectorMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ReadingStore(ObjectMapper json, TransactionTemplate readingTransactions, ReadingSessionMapper sessionMapper, ReadingRunMapper runMapper, ReadingKbMapper kbMapper, ReadingHistorySummaryMapper summaryMapper, ReadingVectorMapper vectorMapper) {
        this.json = json;
        this.transactions = readingTransactions;
        this.sessionMapper = sessionMapper;
        this.runMapper = runMapper;
        this.kbMapper = kbMapper;
        this.summaryMapper = summaryMapper;
        this.vectorMapper = vectorMapper;
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("数据序列化失败", e);
        }
    }

    public Integer vectorDimensions(String kbId) {
        return vectorMapper.dimensions(kbId);
    }

    public void deleteVectors(String kbId) {
        vectorMapper.deleteKnowledge(kbId);
    }

    /*
    * ids：知识库id
    * modelId：向量模型id
    *
    * */
    public List<Source> vectorSearch(List<String> ids, String modelId, int dimensions, float[] vector, int limit) {
        if (ids.isEmpty()) return List.of();
        return vectorMapper.search(ids, modelId, dimensions, Arrays.toString(vector), limit);
    }

    public List<String> fullTextVectorIds(String kbId, String generation, float[] vector, int limit) {
        return vectorMapper.fullTextIds(kbId, generation, Arrays.toString(vector), limit);
    }

    public List<Map<String, Object>> knowledgeBases() {
        return kbMapper.selectList(Wrappers.<ReadingKbEntity>lambdaQuery().orderByDesc(ReadingKbEntity::getCreatedAt)).stream().map(this::kbMap).toList();
    }

    public ReadingKbEntity knowledgeBase(String id) {
        var kb = kbMapper.selectById(id);
        if (kb == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        return kb;
    }

    public Map<String, Object> kb(String id) {
        return kbMap(knowledgeBase(id));
    }

    public void addKb(String id, String filename, String format, String path) {
        var kb = new ReadingKbEntity();
        kb.setId(id);
        kb.setName(filename);
        kb.setFilename(filename);
        kb.setFormat(format);
        kb.setPath(path);
        kb.setStatus("PENDING");
        kb.setGeneration(UUID.randomUUID().toString());
        kbMapper.insert(kb);
    }

    public void status(String id, String status, String error) {
        kbMapper.update(Wrappers.<ReadingKbEntity>lambdaUpdate().eq(ReadingKbEntity::getId, id).set(ReadingKbEntity::getStatus, status).set(ReadingKbEntity::getError, error));
    }

    public void resetKb(String id) {
        transactions.executeWithoutResult(tx -> {
            kbMapper.update(Wrappers.<ReadingKbEntity>lambdaUpdate().eq(ReadingKbEntity::getId, id).set(ReadingKbEntity::getStatus, "PENDING").set(ReadingKbEntity::getError, "").set(ReadingKbEntity::getChunkCount, 0).set(ReadingKbEntity::getGeneration, UUID.randomUUID().toString()).set(ReadingKbEntity::getModelId, null).set(ReadingKbEntity::getDimensions, null));
            deleteVectors(id);
        });
    }

    public void ready(String id, String generation, int count, String model, int dimensions) {
        int updated = kbMapper.update(Wrappers.<ReadingKbEntity>lambdaUpdate().eq(ReadingKbEntity::getId, id)
                .eq(ReadingKbEntity::getGeneration, generation).eq(ReadingKbEntity::getStatus, "EMBEDDING")
                .set(ReadingKbEntity::getStatus, "READY").set(ReadingKbEntity::getChunkCount, count)
                .set(ReadingKbEntity::getModelId, model).set(ReadingKbEntity::getDimensions, dimensions)
                .set(ReadingKbEntity::getError, ""));
        if (updated != 1) throw new IllegalArgumentException("知识库版本已变化，请重新入库");
    }

    public void renameKb(String id, String name) {
        knowledgeBase(id);
        kbMapper.update(Wrappers.<ReadingKbEntity>lambdaUpdate().eq(ReadingKbEntity::getId, id).set(ReadingKbEntity::getName, required(name, 200)));
    }

    public void deleteKb(String id) {
        transactions.executeWithoutResult(tx -> {
            deleteVectors(id);
            kbMapper.deleteById(id);
        });
    }

    public Map<String, Object> session(String id) {
        var session = sessionMapper.selectById(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        return sessionMap(session);
    }

    public Settings settings(String id) {
        var session = sessionMapper.selectById(id);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
        return session.getSettings();
    }

    public List<Map<String, Object>> sessions() {
        return sessionMapper.selectList(Wrappers.<ReadingSessionEntity>lambdaQuery().orderByDesc(ReadingSessionEntity::getUpdatedAt)).stream().map(this::sessionMap).toList();
    }

    public Map<String, Object> createSession() {
        String id = UUID.randomUUID().toString();
        var session = new ReadingSessionEntity();
        session.setId(id);
        session.setTitle("新对话");
        session.setSettings(new Settings(List.of(), "", false));
        sessionMapper.insert(session);
        return session(id);
    }

    public void settings(String id, Settings settings) {
        session(id);
        sessionMapper.update(Wrappers.<ReadingSessionEntity>lambdaUpdate().eq(ReadingSessionEntity::getId, id)
                .set(ReadingSessionEntity::getSettings, settings, "typeHandler=" + JsonbHandlers.SettingsHandler.class.getName()).setSql("updated_at=now()"));
    }

    public void deleteSession(String id) {
        session(id);
        sessionMapper.deleteById(id);
    }

    public List<Map<String, Object>> history(String session) {
        session(session);
        return runMapper.selectList(Wrappers.<ReadingRunEntity>lambdaQuery().eq(ReadingRunEntity::getSessionId, session).orderByAsc(ReadingRunEntity::getCreatedAt, ReadingRunEntity::getId)).stream().map(this::runMap).toList();
    }

    public Map<String, Object> historySummary(String session) {
        var summary = summaryMapper.selectById(session);
        if (summary == null) return Map.of();
        var row = new LinkedHashMap<String, Object>();
        row.put("summary", summary.getSummary());
        row.put("coveredRunId", summary.getCoveredRunId());
        row.put("pendingRunId", summary.getPendingRunId());
        row.put("pendingOffset", summary.getPendingOffset());
        return row;
    }

    public void saveHistorySummary(String session, String summary, String coveredRunId) {
        saveHistorySummaryProgress(session, summary, coveredRunId, "", 0);
    }

    public void saveHistorySummaryProgress(String session, String summary, String coveredRunId, String pendingRunId, int offset) {
        var entity = new ReadingHistorySummaryEntity();
        entity.setSessionId(session);
        entity.setSummary(summary);
        entity.setCoveredRunId(coveredRunId);
        entity.setPendingRunId(pendingRunId);
        entity.setPendingOffset(offset);
        summaryMapper.upsert(entity);
    }

    public void startRun(String id, String session, String question) {
        transactions.executeWithoutResult(tx -> {
            var run = new ReadingRunEntity();
            run.setId(id);
            run.setSessionId(session);
            run.setQuestion(question);
            run.setStatus("RUNNING");
            runMapper.insert(run);
            sessionMapper.touchForRun(session, question.substring(0, Math.min(40, question.length())));
        });
    }

    public void checkpoint(String id, String answer, String status, List<Source> sources, List<Event> events) {
        var run = new ReadingRunEntity();
        run.setId(id);
        run.setAnswer(answer);
        run.setStatus(status);
        run.setSources(sources);
        run.setEvents(events);
        runMapper.updateById(run);
    }

    private Map<String, Object> kbMap(ReadingKbEntity kb) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", kb.getId());
        row.put("name", kb.getName());
        row.put("filename", kb.getFilename());
        row.put("format", kb.getFormat());
        row.put("path", kb.getPath());
        row.put("status", kb.getStatus());
        row.put("error", kb.getError());
        row.put("chunkCount", kb.getChunkCount());
        row.put("modelId", kb.getModelId());
        row.put("dimensions", kb.getDimensions());
        row.put("generation", kb.getGeneration());
        row.put("createdAt", kb.getCreatedAt().toInstant().toString());
        return row;
    }

    private Map<String, Object> sessionMap(ReadingSessionEntity session) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", session.getId());
        row.put("title", session.getTitle());
        row.put("settings", json.convertValue(session.getSettings(), Object.class));
        row.put("createdAt", session.getCreatedAt().toInstant().toString());
        row.put("updatedAt", session.getUpdatedAt().toInstant().toString());
        return row;
    }

    private Map<String, Object> runMap(ReadingRunEntity run) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", run.getId());
        row.put("sessionId", run.getSessionId());
        row.put("question", run.getQuestion());
        row.put("answer", run.getAnswer());
        row.put("status", run.getStatus());
        row.put("sources", json.convertValue(run.getSources(), Object.class));
        row.put("events", json.convertValue(run.getEvents(), Object.class));
        row.put("createdAt", run.getCreatedAt().toInstant().toString());
        return row;
    }

    public static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("文本不能为空，且长度不能超过 " + max);
        return value.strip();
    }
}
