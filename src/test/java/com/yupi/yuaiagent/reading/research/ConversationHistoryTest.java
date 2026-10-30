package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.model.HistoryTokenCounter;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationHistoryTest {
    ReadingStore store = mock(ReadingStore.class);
    ReadingProperties p = new ReadingProperties();
    HistoryTokenCounter counter = new HistoryTokenCounter();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ResearchRun run;

    @BeforeEach void setup() {
        run = new ResearchRun("current", "session", new Settings(List.of(), "collaborative-research", false),
                new Mode("collaborative-research", "研究", "", ""), store, new SseEmitter(), p);
        when(store.historySummary("session")).thenReturn(Map.of());
        when(store.history("session")).thenReturn(List.of());
    }
    @AfterEach void close() { executor.shutdownNow(); }
    Map<String, Object> row(String id, String question, String answer) {
        return Map.of("id", id, "status", "COMPLETED", "question", question, "answer", answer);
    }
    ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    String prepare(ChatModel model) { return new ConversationHistory(model, store, p, executor).prepare(run); }
    void smallBudget() { p.setHistorySummaryThresholdTokens(200); p.setHistorySummaryTargetTokens(64); }
    String longAnswer() { return "不同观点的成立条件与反向证据。".repeat(200); }
    void noWrites() {
        verify(store, never()).saveHistorySummary(anyString(), anyString(), anyString());
        verify(store, never()).saveHistorySummaryProgress(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void emptyHistoryDoesNotCallOrPersist() {
        var model = mock(ChatModel.class);
        assertEquals("", prepare(model)); verifyNoInteractions(model); noWrites();
    }

    @Test void defaultsAreWholeHistory12000To3000WithIndependentGenerationLimit() {
        assertEquals(12000, p.getHistorySummaryThresholdTokens());
        assertEquals(3000, p.getHistorySummaryTargetTokens());
        assertTrue(p.getHistorySummaryOutputTokens() > p.getHistorySummaryTargetTokens());
    }

    @Test void belowThresholdRetainsCompleteQuestionsAndAnswersWithoutSummarization() {
        when(store.history("session")).thenReturn(List.of(row("a", "问题一", "原始回答一[S9]"), row("b", "问题二", "原始回答二")));
        var model = mock(ChatModel.class);
        String result = prepare(model);
        assertTrue(result.contains("问题一") && result.contains("原始回答一") && result.contains("问题二") && result.contains("原始回答二"));
        assertFalse(result.contains("[S9]")); verifyNoInteractions(model); noWrites();
    }

    @Test void cachedSummaryAndNewExchangesAccumulateWithoutAdvancingCursor() {
        when(store.historySummary("session")).thenReturn(Map.of("summary", "既有整体摘要", "coveredRunId", "old"));
        when(store.history("session")).thenReturn(List.of(row("old", "已覆盖原文哨兵", "旧回答"), row("new", "新问题", "新回答")));
        var model = mock(ChatModel.class);
        String result = prepare(model);
        assertTrue(result.contains("既有整体摘要") && result.contains("新回答"));
        assertFalse(result.contains("已覆盖原文哨兵")); verifyNoInteractions(model); noWrites();
    }

    @Test void thresholdCompactsExistingSummaryAndAllNewExchangesTogetherOnce() {
        smallBudget();
        when(store.historySummary("session")).thenReturn(Map.of("summary", "已有整体记忆", "coveredRunId", "old"));
        when(store.history("session")).thenReturn(List.of(row("old", "已覆盖问题哨兵", "旧回答"), row("new", "新增问题", longAnswer())));
        AtomicInteger calls = new AtomicInteger();
        String result = prepare(prompt -> {
            calls.incrementAndGet();
            String input = prompt.getInstructions().getLast().getText();
            assertTrue(input.contains("已有整体记忆") && input.contains("新增问题") && input.contains("反向证据"));
            assertFalse(input.contains("已覆盖问题哨兵"));
            assertEquals(8192, prompt.getOptions().getMaxTokens());
            return response("整体压缩后的核心判断和边界。");
        });
        assertEquals(1, calls.get()); assertTrue(counter.count(result) <= 64);
        verify(store).saveHistorySummary("session", result, "new");
        verify(store, never()).saveHistorySummaryProgress(anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test void exactThresholdTriggersCompressionAndDoesNotRefillRawHistory() {
        p.setHistorySummaryTargetTokens(64);
        String text = "\n历史用户问题：问题\n历史助手回答：" + longAnswer() + "\n";
        p.setHistorySummaryThresholdTokens(counter.count(text));
        when(store.history("session")).thenReturn(List.of(row("a", "问题", longAnswer())));
        String result = prepare(prompt -> response("压缩结果"));
        assertEquals("压缩结果", result); verify(store).saveHistorySummary("session", result, "a");
    }

    @Test void compressionLeavesRoomForFollowingExchangesWithoutAnotherCall() {
        smallBudget();
        when(store.history("session")).thenReturn(List.of(row("a", "问题", longAnswer())));
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response("整体摘要"));
        assertEquals("整体摘要", prepare(model));
        when(store.historySummary("session")).thenReturn(Map.of("summary", "整体摘要", "coveredRunId", "a"));
        when(store.history("session")).thenReturn(List.of(row("a", "问题", longAnswer()), row("b", "追问", "短回答")));
        assertTrue(prepare(model).contains("短回答"));
        verify(model, times(1)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(store, never()).saveHistorySummary(eq("session"), anyString(), eq("b"));
    }

    @Test void unsuccessfulRequestsKeepQuestionsButExcludeAnswersAndCurrentOrRunningRecords() {
        var failed = Map.<String, Object>of("id", "failed", "status", "FAILED", "question", "失败用户问题哨兵", "answer", "失败助手哨兵");
        var current = row("current", "当前用户哨兵", "当前助手");
        var running = Map.<String, Object>of("id", "running", "status", "RUNNING", "question", "运行中哨兵", "answer", "回答");
        when(store.history("session")).thenReturn(List.of(failed, current, running));
        var model = mock(ChatModel.class);
        String result = prepare(model);
        assertTrue(result.contains("失败用户问题哨兵"));
        assertFalse(result.contains("失败助手哨兵") || result.contains("当前用户哨兵") || result.contains("运行中哨兵"));
        verifyNoInteractions(model);
    }

    @Test void oversizedOutputGetsOneRecompressionBeforePersisting() {
        smallBudget();
        when(store.history("session")).thenReturn(List.of(row("new", "问题", longAnswer())));
        AtomicInteger calls = new AtomicInteger();
        String result = prepare(prompt -> calls.getAndIncrement() == 0 ? response("仍然超长的摘要。".repeat(100)) : response("合格摘要"));
        assertEquals(2, calls.get()); assertEquals("合格摘要", result);
        verify(store).saveHistorySummary("session", result, "new");
    }

    @Test void twoOversizedOutputsFallBackWithoutAdvancingCursor() {
        smallBudget();
        when(store.historySummary("session")).thenReturn(Map.of("summary", "旧记忆", "coveredRunId", "old"));
        when(store.history("session")).thenReturn(List.of(row("old", "旧问题", "旧回答"), row("new", "新增问题", longAnswer())));
        AtomicInteger calls = new AtomicInteger();
        String result = prepare(prompt -> { calls.incrementAndGet(); return response(longAnswer()); });
        assertEquals(2, calls.get()); assertTrue(result.contains("未完成"));
        assertTrue(counter.count(result) <= 200); noWrites();
    }

    @Test void modelFailureUsesBoundedMarkedFallbackAndPreservesOriginalCursor() {
        smallBudget();
        when(store.history("session")).thenReturn(List.of(row("new", "新问题哨兵", longAnswer())));
        String result = prepare(prompt -> { throw new IllegalStateException("服务异常"); });
        assertTrue(result.contains("未完成") && result.contains("新问题哨兵"));
        assertTrue(counter.count(result) <= 200); noWrites();
    }

    @Test void truncatedOrBlankSummaryNeverGetsPersisted() {
        smallBudget();
        when(store.history("session")).thenReturn(List.of(row("new", "问题", longAnswer())));
        var generation = mock(Generation.class, RETURNS_DEEP_STUBS);
        when(generation.getOutput()).thenReturn(new AssistantMessage("不完整摘要"));
        when(generation.getMetadata().getFinishReason()).thenReturn("length");
        assertTrue(prepare(prompt -> new ChatResponse(List.of(generation))).contains("未完成"));
        assertTrue(prepare(prompt -> response(" ")).contains("未完成")); noWrites();
    }

    @Test void missingCursorDiscardsStaleMemoryAndRetainsOriginalRecords() {
        when(store.historySummary("session")).thenReturn(Map.of("summary", "过期记忆哨兵", "coveredRunId", "removed"));
        when(store.history("session")).thenReturn(List.of(row("existing", "真实问题哨兵", "真实回答")));
        var model = mock(ChatModel.class);
        String result = prepare(model);
        assertFalse(result.contains("过期记忆哨兵")); assertTrue(result.contains("真实问题哨兵"));
        verifyNoInteractions(model);
    }

    @Test void oversizedLegacySummaryIsMigratedEvenBeforeTriggerThreshold() {
        p.setHistorySummaryThresholdTokens(1000); p.setHistorySummaryTargetTokens(64);
        when(store.historySummary("session")).thenReturn(Map.of("summary", "旧版长摘要。".repeat(60), "coveredRunId", "old"));
        when(store.history("session")).thenReturn(List.of(row("old", "已覆盖哨兵", "旧回答")));
        String result = prepare(prompt -> response("迁移后摘要"));
        assertEquals("迁移后摘要", result);
        verify(store).saveHistorySummaryProgress("session", result, "old", "", 0);
    }

    @Test void longRecordPersistsFragmentAndResumesFromItsExactOffset() {
        smallBudget(); p.setHistorySummaryBatchTokens(128); p.setHistorySummaryMaxCalls(1);
        var original = row("long", "问题开头独立哨兵", longAnswer());
        when(store.history("session")).thenReturn(List.of(original));
        assertTrue(prepare(prompt -> response("已压缩前段")).contains("未完成"));
        var offset = ArgumentCaptor.forClass(Integer.class);
        verify(store).saveHistorySummaryProgress(eq("session"), eq("已压缩前段"), eq(""), eq("long"), offset.capture());
        assertTrue(offset.getValue() > 0);
        when(store.historySummary("session")).thenReturn(Map.of("summary", "已压缩前段", "coveredRunId", "", "pendingRunId", "long", "pendingOffset", offset.getValue()));
        prepare(prompt -> {
            String input = prompt.getInstructions().getLast().getText();
            assertTrue(input.contains("已压缩前段")); assertFalse(input.contains("问题开头独立哨兵"));
            return response("继续压缩所得摘要");
        });
        verify(store).saveHistorySummaryProgress(eq("session"), eq("继续压缩所得摘要"), eq(""), eq("long"), intThat(n -> n > offset.getValue()));
        verify(store, never()).saveHistorySummary(anyString(), anyString(), anyString());
    }

    @Test void fullRecordCursorAdvancesOnlyAfterAllFragmentsAndThenReusesSummary() {
        smallBudget(); p.setHistorySummaryBatchTokens(128); p.setHistorySummaryMaxCalls(50);
        when(store.history("session")).thenReturn(List.of(row("long", "完整历史", longAnswer())));
        AtomicInteger calls = new AtomicInteger();
        String result = prepare(prompt -> {
            verify(store, never()).saveHistorySummary(anyString(), anyString(), anyString());
            calls.incrementAndGet();
            return response("整体累计摘要");
        });
        assertTrue(calls.get() > 1);
        verify(store, atLeastOnce()).saveHistorySummaryProgress(eq("session"), anyString(), eq(""), eq("long"), anyInt());
        verify(store, times(1)).saveHistorySummary("session", result, "long");
        when(store.historySummary("session")).thenReturn(Map.of("summary", result, "coveredRunId", "long"));
        var model = mock(ChatModel.class);
        assertTrue(prepare(model).contains(result)); verifyNoInteractions(model);
    }

    @Test void staleFragmentCannotSkipOrDuplicateOriginalContent() {
        when(store.history("session")).thenReturn(List.of(row("old", "较早问答哨兵", "旧回答"), row("new", "后续问答哨兵", "回答")));
        when(store.historySummary("session")).thenReturn(Map.of("summary", "错误片段摘要哨兵", "coveredRunId", "old", "pendingRunId", "new", "pendingOffset", Integer.MAX_VALUE));
        var model = mock(ChatModel.class);
        String result = prepare(model);
        assertTrue(result.contains("较早问答哨兵") && result.contains("后续问答哨兵"));
        assertFalse(result.contains("错误片段摘要哨兵")); verifyNoInteractions(model); noWrites();
    }

    @Test void zeroCallBudgetFallsBackWithoutCallingModel() {
        smallBudget(); p.setHistorySummaryMaxCalls(0);
        when(store.history("session")).thenReturn(List.of(row("new", "问题", longAnswer())));
        var model = mock(ChatModel.class);
        assertTrue(prepare(model).contains("未完成")); verifyNoInteractions(model); noWrites();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stopAndShutdownCancelSummaryWithoutFallingBack(boolean shutdown) throws Exception {
        smallBudget();
        when(store.history("session")).thenReturn(List.of(row("new", "近期问题", longAnswer())));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = worker.submit(() -> prepare(prompt -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return response("迟到摘要");
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            if (shutdown) run.interrupt(); else run.cancel();
            var error = assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS));
            assertEquals(shutdown ? "INTERRUPTED" : "CANCELLED", ((ResearchRun.Stopped) error.getCause()).status);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS)); noWrites();
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
