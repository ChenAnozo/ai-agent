package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.model.CitationFormat;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;
import static com.yupi.yuaiagent.reading.research.ReadingToolResults.InterestPoint;

/**
 * Request-local evidence and cancellation; no mutable agent state is shared between chats.
 */
public class ResearchRun {
    public final String id, sessionId;
    public final Settings settings;
    public final Mode mode;
    public final ResearchTemplate template;
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public final List<Source> sources = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public boolean fullBookRead;
    public boolean managedEvidence;
    private final Map<String, Source> deduplicated = new HashMap<>();
    private final ReadingStore store;
    private final SseEmitter emitter;
    private final ReadingProperties p;
    private volatile String status = "RUNNING";
    private String currentAnswer = "";
    private volatile boolean serviceStopping;
    private PendingSelection pendingSelection;
    private InterestSelectionRequest previousSelection;

    private record PendingSelection(String id, int round, List<InterestPoint> candidates,
                                    CompletableFuture<List<InterestPoint>> future) { }
    private int tools;
    private int evidenceCharacters;
    private int traceRound, traceStep, traceOperation;
    private final Deque<Map<String, Object>> toolTrace = new ArrayDeque<>();

    public void traceRound(int round) {
        traceRound = round;
        traceStep = 0;
    }

    public void traceStep(int step) {
        traceStep = step;
    }

    public void beginToolTrace(String tool, String origin, Object arguments, String providerCallId, String pointId) {
        var trace = new LinkedHashMap<String, Object>();
        trace.put("operationId", "R" + traceRound + "-O" + (++traceOperation));
        trace.put("tool", tool);
        trace.put("origin", origin);
        trace.put("arguments", arguments);
        if (!providerCallId.isBlank()) trace.put("providerCallId", providerCallId);
        if (!pointId.isBlank()) trace.put("pointId", pointId);
        if (!toolTrace.isEmpty()) trace.put("parentOperationId", toolTrace.peek().get("operationId"));
        toolTrace.push(trace);
        event("tool_call", trace);
    }

    public void endToolTrace() {
        toolTrace.pop();
    }

    public ResearchRun(String id, String sessionId, Settings settings, Mode mode, ReadingStore store, SseEmitter emitter, ReadingProperties p) {
        this(id, sessionId, settings, mode, store, emitter, p, mode.id().equals("collaborative-research") ? new ResearchTemplates(p).effective(settings) : null);
    }

    public ResearchRun(String id, String sessionId, Settings settings, Mode mode, ReadingStore store, SseEmitter emitter, ReadingProperties p, ResearchTemplate template) {
        this.id = id;
        this.sessionId = sessionId;
        this.settings = settings;
        this.mode = mode;
        this.template = template;
        this.store = store;
        this.emitter = emitter;
        this.p = p;
    }

    public static class Stopped extends RuntimeException {
        final String status;

        public Stopped(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public void check() {
        if (serviceStopping) throw new Stopped("INTERRUPTED", "服务中断，本次研究未完成");
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new Stopped("CANCELLED", "用户停止了本次生成");
    }

    public synchronized void cancel() {
        cancelled.set(true);
        if (pendingSelection != null) pendingSelection.future().completeExceptionally(new Stopped("CANCELLED", "用户停止了本次研究"));
    }

    public synchronized void interrupt() {
        serviceStopping = true;
        if (pendingSelection != null) pendingSelection.future().completeExceptionally(new Stopped("INTERRUPTED", "服务中断，本次研究未完成"));
    }

    public synchronized void confirmInterests(InterestSelectionRequest request) {
        check();
        if (previousSelection != null && previousSelection.selectionId().equals(request.selectionId())) {
            if (new HashSet<>(previousSelection.selectedIds()).equals(new HashSet<>(request.selectedIds()))) return;
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "这次兴趣点选择已经确认，不能修改");
        }
        if (pendingSelection == null || !status.equals("WAITING_FOR_SELECTION") || !pendingSelection.id().equals(request.selectionId()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "选择请求已失效，请刷新当前研究状态");
        var selected = pendingSelection.candidates().stream().filter(point -> request.selectedIds().contains(point.id())).toList();
        if (selected.size() != 2) throw new IllegalArgumentException("所选兴趣点不属于本次候选列表");
        previousSelection = request;
        pendingSelection.future().complete(selected);
    }

    List<InterestPoint> awaitInterestChoice(List<InterestPoint> candidates, int round) throws Exception {
        PendingSelection pending;
        synchronized (this) {
            check();
            if (candidates.size() != 4 || pendingSelection != null) throw new IllegalArgumentException("人工选择需要四个候选且不能重复等待");
            pending = new PendingSelection(UUID.randomUUID().toString(), round, List.copyOf(candidates), new CompletableFuture<>());
            pendingSelection = pending;
            status = "WAITING_FOR_SELECTION";
        }
        try {
            event("interest_candidates", Map.of("selectionId", pending.id(), "round", round, "candidates", pending.candidates()));
            send("citations", List.copyOf(sources));
            send("answer", currentAnswer);
            send("paused", Map.of("runId", id, "status", status));
            emitter.complete(); // The task survives this connection; subsequent progress is available via history polling.
            List<InterestPoint> selected;
            while (true) {
                check();
                try {
                    selected = pending.future().get(200, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException ignored) {
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception cause) throw cause;
                    throw e;
                }
            }
            check();
            synchronized (this) {
                status = "RUNNING";
                pendingSelection = null;
            }
            event("interest_choice", Map.of("selectionId", pending.id(), "round", round,
                    "selectedIds", selected.stream().map(InterestPoint::id).toList()));
            return selected;
        } finally {
            synchronized (this) {
                if (pendingSelection == pending) {
                    pendingSelection = null;
                    status = "RUNNING";
                }
            }
        }
    }

    public void reserveTool() {
        check();
        if (++tools > p.getMaxToolCalls()) throw new Stopped("LIMIT_REACHED", "达到工具调用次数上限");
    }

    public <T> T await(ExecutorService executor, Callable<T> operation) throws Exception {
        check();
        Future<T> future = executor.submit(() -> {
            check();
            return operation.call();
        });
        try {
            while (true) {
                check();
                try {
                    T value = future.get(200, TimeUnit.MILLISECONDS);
                    check();
                    return value;
                } catch (TimeoutException ignored) {
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception cause) throw cause;
                    throw e;
                }
            }
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public Source source(Source raw) {
        String key = raw.type() + ":" + raw.id() + ":" + raw.url();
        Source existing = deduplicated.get(key);
        if (existing != null) return existing;
        int allowance = p.getMaxContextCharacters() - evidenceCharacters;
        if (allowance <= 0) throw new Stopped("LIMIT_REACHED", "达到资料上下文上限");
        String text = raw.text() == null ? "" : raw.text();
        if (text.length() > allowance) text = text.substring(0, allowance) + "\n[资料已截断]";
        Source source = new Source("S" + (sources.size() + 1), raw.type(), raw.title(), raw.location(), raw.url(), text);
        sources.add(source);
        deduplicated.put(key, source);
        evidenceCharacters += text.length();
        return source;
    }

    public Source exactSource(Source raw, int maximum) {
        String key = raw.type() + ":" + raw.id() + ":" + raw.url();
        Source existing = deduplicated.get(key);
        if (existing != null) return existing;
        int allowed = fullBookRead ? maximum : p.getMaxContextCharacters();
        if (!managedEvidence && raw.text().length() + evidenceCharacters > allowed)
            throw new IllegalArgumentException("原文将超过资料上下文上限，请缩小范围；不会截断原文");
        Source source = new Source("S" + (sources.size() + 1), raw.type(), raw.title(), raw.location(), raw.url(), raw.text());
        sources.add(source);
        deduplicated.put(key, source);
        evidenceCharacters += raw.text().length();
        return source;
    }

    public void event(String type, Object data) {
        if ((type.equals("research_draft") || type.equals("answer_revision")) && data instanceof Map<?, ?> answerData
                && answerData.get("answer") instanceof String answer) currentAnswer = answer;
        if (data instanceof Map<?, ?> map) {
            var tagged = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> tagged.put(key.toString(), value));
            if (traceRound > 0) {
                tagged.putIfAbsent("round", traceRound);
                tagged.putIfAbsent("step", traceStep);
            }
            if (type.startsWith("tool") && !toolTrace.isEmpty()) {
                var trace = toolTrace.peek();
                // Tools already emit their own events; the dispatcher records the complete call once.
                if (type.equals("tool_call") && Objects.equals(map.get("tool"), trace.get("tool")) && !map.containsKey("operationId"))
                    return;
                trace.forEach(tagged::putIfAbsent);
            }
            data = tagged;
        }
        Event event = new Event(type, data);
        events.add(event);
        store.checkpoint(id, currentAnswer, status, sources, events);
        send(type, data);
    }

    public void send(String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
        } catch (Exception ignored) { /* History remains accessible after disconnect. */ }
    }

    public String validateCitations(String answer) {
        answer = CitationFormat.normalize(answer);
        Set<String> valid = new HashSet<>();
        sources.forEach(s -> valid.add(s.id()));
        return Pattern.compile("\\[S\\d+\\]").matcher(answer).replaceAll(m -> valid.contains(m.group().substring(1, m.group().length() - 1)) ? m.group() : "（无效来源编号，已移除）");
    }

    public String incomplete(String reason) {
        if (!currentAnswer.isBlank()) return reason + "。本次研究未完成，以下保留已生成的回答：\n\n" + currentAnswer;
        StringBuilder answer = new StringBuilder(reason + "。本次研究未完成，以下仅为已取得的证据，不能视为完整结论。\n");
        for (Source source : sources.stream().limit(8).toList())
            answer.append("\n- [").append(source.id()).append("] ").append(source.title()).append("（").append(source.location()).append("）：")
                    .append(source.text(), 0, Math.min(300, source.text().length()));
        if (sources.isEmpty()) answer.append("\n尚未取得可引用的证据。");
        return answer.toString();
    }

    public void finish(String answer, String status) {
        this.status = status;
        answer = validateCitations(answer);
        try {
            store.checkpoint(id, answer, status, sources, events);
        } catch (Exception e) {
            status = "FAILED";
            send("error", "数据库保存失败，本次结果未能持久化");
        }
        send("citations", List.copyOf(sources));
        send("answer", answer);
        send("complete", Map.of("runId", id, "status", status));
        emitter.complete();
    }
}
