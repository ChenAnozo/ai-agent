package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.book.BookTexts;
import com.yupi.yuaiagent.reading.book.BookSearch;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

class ReadingToolsTest {
    ReadingStore store = mock(ReadingStore.class);
    BookTexts maps = mock(BookTexts.class);
    BookSearch search = mock(BookSearch.class);
    ReadingProperties p = new ReadingProperties();
    ExecutorService calls = Executors.newFixedThreadPool(2);
    ObjectMapper json = new ObjectMapper();
    String id = "00000000-0000-0000-0000-000000000001";
    BookTexts.Book book;
    ResearchRun run;
    ReadingTools tools;

    @BeforeEach
    void setup() throws Exception {
        when(store.encode(any())).thenAnswer(i -> json.writeValueAsString(i.getArgument(0)));
        String text = "第一章\n主张：群众动员受到组织结构约束。\n第二章\n反证：此处引用他人观点。";
        book = new BookTexts.Book(id, "书籍", "g", text, List.of());
        run = new ResearchRun("run", "session", new Settings(List.of(id), "collaborative-research", false), new Mode("collaborative-research", "协作研究", "", "读原文"), store, new SseEmitter(), p);
        when(maps.load(id)).thenReturn(book);
    }

    @AfterEach
    void close() {
        calls.shutdownNow();
    }

    ReadingTools.State state() {
        tools = new ReadingTools(maps, search, store, p);
        var state = tools.new State(run, calls);
        state.investigation("局部问题");
        return state;
    }

    BookSearch.Hit hit(int start, int end) {
        return new BookSearch.Hit("p" + start, start, end, 1, 2, 0.03);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 5})
    void automaticSearchSplitsQueriesWhileManualFollowUpKeepsSingleQuery(int topK) throws Exception {
        p.setInvestigatorHybridTopK(topK);
        var state = state();
        when(search.search(book, "组织 约束", "什么条件下组织影响动员？", topK, true)).thenReturn(List.of(hit(0, 10)));
        state.investigationSearchResult(id, "组织 约束", "什么条件下组织影响动员？", "兴趣点检索", "R1-I1");
        verify(search).search(book, "组织 约束", "什么条件下组织影响动员？", topK, true);
        state.searchPassages(id, "补查条件");
        verify(search).search(book, "补查条件", topK, true);
        verifyNoMoreInteractions(search);
    }

    @Test
    void searchReturnsFullTextDeduplicatesOverlapAndReusesAcrossRounds() throws Exception {
        var state = state();
        when(search.search(eq(book), anyString(), eq(3), eq(true))).thenReturn(List.of(hit(0, 20), hit(10, 30)));
        var result = json.readTree(state.searchPassages(id, "关系"));
        assertEquals(30, result.path("newCharacters").asInt());
        assertEquals(book.text().substring(0, 20), run.sources.get(0).text());
        assertEquals(book.text().substring(20, 30), run.sources.get(1).text());
        assertEquals(2, state.investigationSources().size());
        assertFalse(result.has("hits"));
        var repeat = json.readTree(state.searchPassages(id, "补查"));
        assertEquals(0, repeat.path("newCharacters").asInt());
        assertEquals(0, repeat.path("sources").size());
        assertEquals(2, run.sources.size());
        state.investigation("第二轮");
        var next = json.readTree(state.searchPassages(id, "再次核对"));
        assertEquals(2, next.path("sources").size());
        assertEquals(2, state.investigationSources().size());
        assertEquals(2, run.sources.size());
    }

    @Test
    void searchReturnsFullPassageAndExpansionOnlyAddsMissingContext() throws Exception {
        book = new BookTexts.Book(id, "书籍", "g", "前".repeat(100) + "文".repeat(1190) + "分片末尾独立标记" + "后".repeat(100), List.of());
        when(maps.load(id)).thenReturn(book);
        var state = state();
        int start = 100, end = book.text().length() - 100;
        when(search.search(book, "原文", 3, true)).thenReturn(List.of(hit(start, end)));
        var output = json.readTree(state.searchPassages(id, "原文"));
        assertFalse(output.has("retrievalMode"));
        assertEquals(book.text().substring(start, end), output.path("sources").get(0).path("text").asText());
        assertTrue(output.path("sources").get(0).path("text").asText().contains("分片末尾独立标记"));
        assertEquals("S1", output.path("passages").get(0).path("sourceIds").get(0).asText());
        assertFalse(output.path("passages").get(0).has("text"));
        assertEquals(Set.of("S1"), state.investigationSources());
        assertTrue(json.readTree(state.readPassage(id, start, end, 0, 0)).path("alreadyProvided").asBoolean());
        var expanded = json.readTree(state.readPassage(id, start, end, 100, 100));
        String delta = expanded.path("source").path("text").asText();
        assertTrue(delta.contains("前".repeat(100)) && delta.contains("后".repeat(100)));
        assertFalse(delta.contains("文".repeat(10)));
        verify(search, times(1)).search(book, "原文", 3, true);
    }

    @Test
    void searchDeduplicatesInitialSearchFollowUpAndResendsEvidenceInNextInvestigation() throws Exception {
        var state = state();
        when(search.search(book, "初始", "问题", 3, true)).thenReturn(List.of(hit(0, 20)));
        state.investigationSearchResult(id, "初始", "问题", "兴趣点检索", "R1-I1");
        when(search.search(book, "补查", 3, true)).thenReturn(List.of(hit(10, 30)));
        var followUp = json.readTree(state.searchPassages(id, "补查"));
        assertEquals(10, followUp.path("newCharacters").asInt());
        assertEquals(List.of("S1", "S2"), json.convertValue(followUp.path("passages").get(0).path("sourceIds"), List.class));
        assertEquals(book.text().substring(20, 30), followUp.path("sources").get(0).path("text").asText());
        state.investigation("下一轮");
        when(search.search(book, "复核", 3, true)).thenReturn(List.of(hit(0, 30)));
        var next = json.readTree(state.searchPassages(id, "复核"));
        assertEquals(0, next.path("newCharacters").asInt());
        assertEquals(2, next.path("sources").size());
        assertEquals(2, run.sources.size());
        assertEquals(Set.of("S1", "S2"), state.investigationSources());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 5})
    void configuredLimitsAndBudgetNeverRegisterUndeliveredPassages(int topK) throws Exception {
        p.setInvestigatorHybridTopK(topK);
        var state = state();
        when(search.search(book, "数量", topK, true)).thenReturn(java.util.stream.IntStream.range(0, 10).mapToObj(i -> hit(i, i + 1)).toList());
        var limited = json.readTree(state.searchPassages(id, "数量"));
        assertEquals(topK, limited.path("passages").size());
        assertEquals(topK, limited.path("retrievedCount").asInt());
        assertEquals(topK, limited.path("deliveredCount").asInt());

        state = state();
        p.setMaxContextCharacters(1);
        p.setSynthesisReserveTokens(0);
        book = new BookTexts.Book(id, "书籍", "g2", "x".repeat(10000), List.of());
        when(maps.load(id)).thenReturn(book);
        when(search.search(book, "预算", topK, true)).thenReturn(List.of(hit(0, 600), hit(1000, 9000)));
        state.investigationInput(List.of(new UserMessage("x".repeat(29000))));
        var output = json.readTree(state.searchPassages(id, "预算"));
        assertEquals(2, output.path("retrievedCount").asInt());
        assertEquals(1, output.path("deliveredCount").asInt());
        assertEquals(600, output.path("sources").get(0).path("text").asText().length());
        assertFalse(output.path("stopReason").asText().isEmpty());
        assertEquals(1, state.investigationSources().size());
        assertTrue(run.sources.stream().filter(s -> s.type().equals("BOOK") && s.location().contains("1000–9000")).findAny().isEmpty());
    }

    @Test
    void toolMenuKeepsExpansionAndBlocksUnselectedBooks() throws Exception {
        var state = state();
        var names = Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(state)).map(t -> t.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("searchPassages", "readPassage"), names);
        assertThrows(IllegalArgumentException.class, () -> state.searchPassages("other", "问题"));
        verifyNoInteractions(search);
    }

    @Test
    void searchBudgetStopsBeforeWholeChunkAndKeepsEarlierEvidence() throws Exception {
        var state = state();
        p.setMaxContextCharacters(1);
        p.setSynthesisReserveTokens(0);
        book = new BookTexts.Book(id, "书籍", "g", "x".repeat(10000), List.of());
        when(maps.load(id)).thenReturn(book);
        when(search.search(eq(book), anyString(), eq(3), eq(true))).thenReturn(List.of(hit(0, 600), hit(1000, 4000)));
        state.investigationInput(List.of(new UserMessage("x".repeat(29000))));
        var result = json.readTree(state.searchPassages(id, "问题"));
        assertEquals(1, result.path("passages").size());
        assertEquals(600, run.sources.getFirst().text().length());
        assertEquals(1, run.sources.size());
        assertNotNull(state.investigationStop());
    }

    @Test
    void emptySearchFailureAndConfiguredHitLimit() throws Exception {
        var state = state();
        when(search.search(eq(book), anyString(), eq(3), eq(true))).thenReturn(List.of());
        assertEquals(0, json.readTree(state.searchPassages(id, "空")).path("sources").size());
        when(search.search(eq(book), anyString(), eq(3), eq(true))).thenThrow(new IllegalArgumentException("检索失败"));
        assertThrows(IllegalArgumentException.class, () -> state.searchPassages(id, "失败"));
        assertTrue(run.sources.isEmpty());
        when(search.search(eq(book), anyString(), eq(3), eq(true))).thenReturn(java.util.stream.IntStream.range(0, 10).mapToObj(i -> hit(i, i + 1)).toList());
        assertEquals(3, json.readTree(state.searchPassages(id, "数量")).path("passages").size());
    }

    @Test
    void settingsIgnoreLegacyRetrievalModesAndPreserveOtherFields() throws Exception {
        for (String legacyMode : List.of("FOCUSED", "BROAD", "INVALID")) {
            var saved = Map.of("knowledgeBaseIds", List.of(id), "modeId", "collaborative-research",
                    "retrievalMode", legacyMode, "researchTemplateId", "my-template", "interestSelectionMode", "MANUAL");
            var settings = json.readValue(json.writeValueAsString(saved), Settings.class);
            assertEquals(new Settings(List.of(id), "collaborative-research", "my-template", "MANUAL"), settings);
            assertFalse(json.readTree(json.writeValueAsString(settings)).has("retrievalMode"));
        }
        var defaults = json.readValue("{}", Settings.class);
        assertEquals("AUTO", defaults.interestSelectionMode());
        assertEquals("", defaults.researchTemplateId());
    }

    @Test
    void searchEvidenceIsIsolatedByBookAndGeneration() throws Exception {
        String second = "00000000-0000-0000-0000-000000000002";
        run = new ResearchRun("run", "session", new Settings(List.of(id, second), "collaborative-research", ""), new Mode("collaborative-research", "协作研究", "", ""), store, new SseEmitter(), p);
        var state = state();
        var other = new BookTexts.Book(second, "另一本书", "g", book.text(), List.of());
        when(maps.load(second)).thenReturn(other);
        when(search.search(any(BookTexts.Book.class), anyString(), eq(3), eq(true))).thenReturn(List.of(hit(0, 10)));
        state.searchPassages(id, "第一本");
        state.searchPassages(second, "第二本");
        assertEquals(2, run.sources.size());
        var updated = new BookTexts.Book(id, "书籍", "g2", book.text(), List.of());
        when(maps.load(id)).thenReturn(updated);
        state.searchPassages(id, "新版");
        assertEquals(3, run.sources.size());
    }

    @Test
    void passageUsesAbsolutePositionAndContinuation() throws Exception {
        var state = state();
        int start = book.text().indexOf("第二章") + 2;
        var first = json.readTree(state.readPassage(id, start, start + 4, 0, 0));
        assertEquals(start, first.path("start").asInt());
        assertEquals(start + 4, first.path("end").asInt());
        assertEquals(start + 4, first.path("nextStart").asInt());
        assertFalse(first.has("nextOffset"));
        var last = json.readTree(state.readPassage(id, start + 4, book.text().length(), 0, 0));
        assertTrue(last.path("nextStart").isNull());
    }

    @Test
    void invalidPositionsCannotReadOutsideFullText() throws Exception {
        var state = state();
        assertThrows(IllegalArgumentException.class, () -> state.readPassage(id, -1, 4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> state.readPassage(id, 0, book.text().length() + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> state.readPassage(id, 4, 4, 0, 0));
        assertTrue(run.sources.isEmpty());
    }

    @Test
    void overlappingAndRepeatedReadsDoNotResendAlreadyProvidedText() throws Exception {
        var state = state();
        state.readPassage(id, 0, 20, 0, 0);
        var overlap = json.readTree(state.readPassage(id, 10, 30, 0, 0));
        assertFalse(overlap.path("source").path("text").asText().contains(book.text().substring(10, 20)));
        assertTrue(overlap.path("source").path("text").asText().contains(book.text().substring(20, 30)));
        var repeated = json.readTree(state.readPassage(id, 0, 20, 0, 0));
        assertTrue(repeated.path("alreadyProvided").asBoolean());
        assertEquals(2, run.sources.size());
    }

    @Test
    void readBudgetsNoTruncationAndCancellation() throws Exception {
        var state = state();
        p.setReadCharacters(10);
        assertThrows(IllegalArgumentException.class, () -> state.readPassage(id, 0, 20, 0, 0));
        assertTrue(run.sources.isEmpty());
        run.cancelled.set(true);
        assertThrows(ResearchRun.Stopped.class, () -> state.readPassage(id, 0, 6, 0, 0));
        verifyNoInteractions(search);
    }

    @Test
    void subsequentReadsUseCurrentBook() throws Exception {
        var state = state();
        state.readPassage(id, 0, 4, 0, 0);
        var updated = new BookTexts.Book(id, "书籍", "g2", book.text().replace("第一章", "新章节"), List.of());
        when(maps.load(id)).thenReturn(updated);
        var result = json.readTree(state.readPassage(id, 0, 6, 0, 0));
        assertTrue(run.sources.stream().anyMatch(source -> source.text().startsWith("新章节")));
        assertFalse(result.path("alreadyProvided").asBoolean());
    }

    @Test
    void intervalCoverageRequiresContinuousOriginalText() {
        assertFalse(ReadingTools.State.covers(List.of(new int[]{0, 5}, new int[]{7, 10}), 0, 10));
        assertTrue(ReadingTools.State.covers(List.of(new int[]{5, 10}, new int[]{0, 7}), 0, 10));
    }

    @Test
    void compactReadKeepsEvidenceAndContinuationWhileHistoryKeepsDiagnostics() throws Exception {
        var state = state();
        var feedback = json.readTree(state.readPassage(id, 0, 6, 0, 0));
        assertFalse(feedback.has("remainingEstimatedTokens"));
        assertFalse(feedback.has("remainingCharacters"));
        assertFalse(feedback.path("source").has("title"));
        assertFalse(feedback.path("source").has("location"));
        assertEquals(0, feedback.path("start").asInt());
        assertEquals(6, feedback.path("end").asInt());
        assertEquals(6, feedback.path("nextStart").asInt());
        assertEquals(run.sources.getFirst().id(), feedback.path("source").path("id").asText());
        assertTrue(feedback.path("source").path("text").asText().contains(book.text().substring(0, 6)));
        var event = (Map<?, ?>) run.events.stream().filter(e -> e.type().equals("tool_result")).findFirst().orElseThrow().data();
        var full = json.valueToTree(event.get("data"));
        assertTrue(full.has("remainingEstimatedTokens"));
        assertTrue(full.has("remainingCharacters"));
        assertEquals(book.name(), full.path("source").path("title").asText());
        assertEquals(full.path("source").path("text"), feedback.path("source").path("text"));
        assertTrue(((Number) event.get("modelFeedbackCharacters")).intValue() < ((Number) event.get("traceCharacters")).intValue());
    }

    @Test
    void endOfBookKeepsNullCursorAndOmitsUnprovidedOptionalFields() throws Exception {
        var state = state();
        var output = json.readTree(state.readPassage(id, 0, book.text().length(), 0, 0));
        assertTrue(output.has("nextStart"));
        assertTrue(output.path("nextStart").isNull());
        assertFalse(output.has("relatedCandidates"));
        assertFalse(output.has("relatedSearchError"));
        assertFalse(output.has("remainingEstimatedTokens"));
        var repeated = json.readTree(state.readPassage(id, 0, book.text().length(), 0, 0));
        assertTrue(repeated.path("alreadyProvided").asBoolean());
        assertEquals(-1, repeated.path("nextStart").asInt());
        assertFalse(repeated.has("source"));
        assertEquals(1, repeated.path("sourceIds").size());
    }

    @Test
    void toolRegistryContainsOnlyHybridSearchAndOriginalReading() {
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(state());
        var names = Arrays.stream(callbacks).map(t -> t.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("searchPassages", "readPassage"), names);
    }

    @Test
    void largeTraceDoesNotConsumeModelBudgetAndNearLimitStillWarns() throws Exception {
        var state = state();
        p.setMaxContextCharacters(1);
        p.setSynthesisReserveTokens(0);
        var compact = new ReadingToolResults.SearchResult(id, List.of(), List.of(), 0, 0, 0, "");
        var trace = Map.of("diagnostic", "x".repeat(100000));
        assertEquals(json.readTree(store.encode(compact)), json.readTree(state.result("searchPassages", new ReadingTools.ToolResult<>(compact, trace, List.of()))));
        assertNull(state.investigationStop());
        state.investigationInput(List.of(new UserMessage("x".repeat(29000))));
        var warned = json.readTree(state.result("searchPassages", new ReadingTools.ToolResult<>(compact, trace, List.of())));
        assertTrue(warned.has("budgetWarning"));
        assertTrue(warned.path("passages").isArray());
        assertNull(state.investigationStop());
        state.investigationInput(List.of(new UserMessage("x".repeat(32000))));
        state.result("searchPassages", new ReadingTools.ToolResult<>(compact, trace, List.of()));
        assertNotNull(state.investigationStop());
    }
}
