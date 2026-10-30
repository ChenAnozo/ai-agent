package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.book.BookTexts;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.knowledge.ReadingRetrieval;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

class ResearchAgentTest {
    ReadingStore store = mock(ReadingStore.class);
    ReadingRetrieval retrieval = mock(ReadingRetrieval.class);
    ModeRegistry modes = mock(ModeRegistry.class);
    ReadingProperties p = new ReadingProperties();
    ResearchAgent agent;
    String kb = UUID.randomUUID().toString();

    @BeforeEach
    void setup() {
        when(store.history(anyString())).thenReturn(List.of());
        when(store.encode(any())).thenAnswer(invocation -> new ObjectMapper().writeValueAsString(invocation.getArgument(0)));
    }

    @AfterEach
    void close() {
        if (agent != null) agent.close();
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private AssistantMessage tool(String name, String args) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, args))).build();
    }

    ResearchRun run(boolean online, String mode) {
        return new ResearchRun("run", "chat", new Settings(List.of(kb), mode, online), new Mode(mode, "模式", "描述", "补查上下文"), store, new SseEmitter(), p);
    }

    private Source evidence() {
        return new Source("chunk", "BOOK", "书籍", "第一章", "", "作者把理解定义为掌握论证。");
    }

    @Test
    void ordinaryRetrievesOnceAndUsesCurrentEvidence() {
        when(retrieval.search(anyList(), anyString())).thenReturn(List.of(evidence()));
        ChatModel model = prompt -> {
            assertTrue(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().isEmpty());
            assertTrue(prompt.getInstructions().getLast().getText().contains("掌握论证"));
            return response(new AssistantMessage("理解需要掌握论证。[S1][S99]"));
        };
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        var run = run(false, "");
        agent.execute(run, "理解是什么？");
        verify(retrieval, times(1)).search(List.of(kb), "理解是什么？");
        verify(store).checkpoint(eq("run"), contains("无效来源编号"), eq("COMPLETED"), anyList(), anyList());
        assertEquals(1, run.sources.size());
    }

    @Test
    void ordinaryRejectsUnexpectedModelToolCalls() {
        when(retrieval.search(anyList(), anyString())).thenReturn(List.of(evidence()));
        agent = new ResearchAgent(prompt -> response(tool("searchKnowledge", "{}")), store, retrieval, modes, p);
        agent.execute(run(false, ""), "问题");
        verify(retrieval, times(1)).search(anyList(), anyString());
        verify(store).checkpoint(eq("run"), contains("未提供的工具调用"), eq("FAILED"), anyList(), anyList());
    }

    @Test
    void retrievalFailureDoesNotCallModel() {
        when(retrieval.search(anyList(), anyString())).thenThrow(new IllegalStateException("检索失败"));
        var model = mock(ChatModel.class);
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        agent.execute(run(false, ""), "问题");
        verifyNoInteractions(model);
        verify(store).checkpoint(eq("run"), contains("检索失败"), eq("FAILED"), anyList(), anyList());
    }

    @Test
    void cancellationPreventsAnyFurtherCall() {
        ChatModel model = mock(ChatModel.class);
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        var run = run(false, "");
        run.cancelled.set(true);
        agent.execute(run, "问题");
        verifyNoInteractions(model, retrieval);
        verify(store).checkpoint(eq("run"), contains("用户停止"), eq("CANCELLED"), anyList(), anyList());
    }

    @Test
    void ordinaryEmptyRetrievalNeverFabricatesAnAnswer() {
        when(retrieval.search(anyList(), anyString())).thenReturn(List.of());
        ChatModel model = mock(ChatModel.class);
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        agent.execute(run(false, ""), "问题");
        verifyNoInteractions(model);
        verify(store).checkpoint(eq("run"), contains("证据不足"), eq("COMPLETED"), anyList(), anyList());
    }

    @Test
    void concurrentSessionRequestsAndSettingsAreRejected() {
        when(retrieval.search(anyList(), anyString())).thenReturn(List.of(evidence()));
        when(store.session("chat")).thenReturn(Map.of("id", "chat"));
        when(retrieval.validate(List.of(kb))).thenReturn(List.of(mock(com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbEntity.class)));
        when(modes.get("")).thenReturn(new Mode("", "普通问答", "描述", "正文"));
        ChatModel model = prompt -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return response(new AssistantMessage("结束"));
        };
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        var request = new MessageRequest("问题", new Settings(List.of(kb), "", false));
        assertEquals(Long.valueOf(0), agent.start("chat", request).getTimeout());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> agent.start("chat", request));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> agent.settings("chat", request.settings()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stopAndShutdownCancelPendingModelWithoutStartingAnotherCall(boolean shutdown) throws Exception {
        when(retrieval.search(anyList(), anyString())).thenReturn(List.of(evidence()));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        ChatModel model = prompt -> {
            count.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return response(new AssistantMessage("迟到回答"));
        };
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        var run = run(false, "");
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = worker.submit(() -> agent.execute(run, "问题"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            if (shutdown) run.interrupt(); else run.cancel();
            task.get(2, TimeUnit.SECONDS);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(1, count.get());
            verify(store).checkpoint(eq("run"), anyString(), eq(shutdown ? "INTERRUPTED" : "CANCELLED"), anyList(), anyList());
            verify(store, never()).checkpoint(eq("run"), anyString(), eq("COMPLETED"), anyList(), anyList());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void fullBookPrefixIsStableAcrossHistoryAndModeChanges() {
        agent = new ResearchAgent(mock(ChatModel.class), store, retrieval, modes, p);
        var book = new BookTexts.Book("book", "书", "gen", "完整正文唯一哨兵", List.of());
        var maps = mock(BookTexts.class);
        when(maps.load(kb)).thenReturn(book);
        agent.readingTools = new ReadingTools(maps, mock(com.yupi.yuaiagent.reading.book.BookSearch.class), store, p);
        var first = run(false, "collaborative-research");
        var a = agent.history(first, "旧问题").fixedMessages();
        when(store.history(anyString())).thenReturn(List.of(Map.of("id", "previous", "status", "COMPLETED", "question", "历史问题", "answer", "历史回答[S1]")));
        var second = new ResearchRun("newrun", "chat", new Settings(List.of(kb), "collaborative-research", false), new Mode("collaborative-research", "模式", "", "新模式指引"), store, new SseEmitter(), p);
        var b = agent.history(second, "新问题").fixedMessages();
        for (int i = 0; i < 3; i++) assertEquals(a.get(i).getText(), b.get(i).getText());
        assertTrue(b.get(2).getText().contains(book.text()));
        assertTrue(b.get(3).getText().contains(second.template.section("collaborationGuidance")));
        assertFalse(b.get(3).getText().contains("新模式指引"));
        assertFalse(b.stream().anyMatch(m -> m.getText().contains("用户提示词：")));
        assertTrue(b.stream().anyMatch(m -> m.getText().contains("历史引用")));
    }

    @Test
    void collaborativeHistoryProvidesCachedSummaryInsteadOfOldAssistantMessages() {
        var model = mock(ChatModel.class);
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        var book = new BookTexts.Book("book", "书", "gen", "完整正文", List.of());
        var maps = mock(BookTexts.class);
        when(maps.load(kb)).thenReturn(book);
        agent.readingTools = new ReadingTools(maps, mock(com.yupi.yuaiagent.reading.book.BookSearch.class), store, p);
        when(store.history("chat")).thenReturn(List.of(Map.of("id", "previous", "status", "COMPLETED", "question", "旧问题", "answer", "旧回答")));
        when(store.historySummary("chat")).thenReturn(Map.of("summary", "用户纠正：关注共存条件", "coveredRunId", "previous"));
        var context = agent.history(run(false, "collaborative-research"), "当前问题");
        var input = context.fixedMessages();
        assertTrue(input.stream().anyMatch(m -> m.getText().contains("用户纠正：关注共存条件")));
        assertTrue(input.stream().anyMatch(m -> m instanceof SystemMessage && m.getText().contains("请自行判断是否采用")));
        assertFalse(input.stream().anyMatch(AssistantMessage.class::isInstance));
        assertEquals("当前问题", input.getLast().getText());
        assertTrue(input.stream().anyMatch(message -> message.getText().contains("完整正文")));
        assertFalse(context.revisionBaseMessages().stream().anyMatch(message -> message.getText().contains("完整正文") || message.getText().contains("以下为所选知识库当前完整原文")));
        assertFalse(context.revisionBaseMessages().stream().anyMatch(message -> message.getText().contains("用户纠正：关注共存条件")));
        assertEquals("当前问题", context.revisionBaseMessages().getLast().getText());
        verifyNoInteractions(model);
    }
    @Test
    void uncompressedHistoryIsInitialOnlyAndCurrentQuestionSurvivesRevision() {
        var model = mock(ChatModel.class);
        agent = new ResearchAgent(model, store, retrieval, modes, p);
        var maps = mock(BookTexts.class);
        when(maps.load(kb)).thenReturn(new BookTexts.Book("book", "书", "gen", "全书独立哨兵", List.of()));
        agent.readingTools = new ReadingTools(maps, mock(com.yupi.yuaiagent.reading.book.BookSearch.class), store, p);
        when(store.history("chat")).thenReturn(List.of(Map.of("id", "previous", "status", "COMPLETED", "question", "历史用户独立哨兵", "answer", "历史助手独立哨兵[S3]")));
        var context = agent.history(run(false, "collaborative-research"), "当前问题独立哨兵");
        String initial = context.fixedMessages().stream().map(Message::getText).reduce("", String::concat);
        String revision = context.revisionBaseMessages().stream().map(Message::getText).reduce("", String::concat);
        assertTrue(initial.contains("历史用户独立哨兵") && initial.contains("历史助手独立哨兵"));
        assertFalse(initial.contains("[S3]"));
        assertFalse(revision.contains("历史用户独立哨兵") || revision.contains("历史助手独立哨兵") || revision.contains("全书独立哨兵"));
        assertTrue(revision.contains("当前问题独立哨兵"));
        verifyNoInteractions(model);
    }

}
