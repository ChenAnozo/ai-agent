package com.yupi.yuaiagent.reading.model;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.research.ResearchRun;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

class ContextBudgetTest {
    ReadingProperties p = new ReadingProperties();
    ResearchRun run = new ResearchRun("r", "s", new Settings(List.of(), "", false), new Mode("", "", "", ""), mock(ReadingStore.class), new SseEmitter(), p);
    ContextBudget budget = new ContextBudget(p, run);

    @Test
    void includesToolArgumentsAndResultsAndReservesSpaceForSynthesis() {
        var messages = List.<Message>of(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "read", "字".repeat(100)))).build(), ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("id", "read", "文".repeat(200)))).build());
        assertEquals(300, budget.characters(messages));
        assertTrue(budget.tokens(messages) > 450);
        p.setContextInputTokens(budget.tokens(messages) + 100);
        assertFalse(budget.fits(messages, true));
        assertTrue(budget.fits(messages, false));
    }

    @Test
    void fullTextHasSeparateConfiguredBudgetAndIsNotSilentlyTrimmed() {
        p.setContextInputTokens(100);
        p.setFullContextInputTokens(50000);
        var messages = List.<Message>of(new UserMessage("全文".repeat(1000)));
        assertThrows(ResearchRun.Stopped.class, () -> budget.require(messages, false));
        run.fullBookRead = true;
        budget.require(messages, true);
        assertEquals("全文".repeat(1000), messages.getFirst().getText());
    }

    @Test
    void revisionCountsOverviewAnswerReportsAndEvidenceTogetherDespiteInitialFullBookRead() {
        run.fullBookRead = true;
        p.setFullContextInputTokens(100000);
        p.setContextInputTokens(1500);
        var revision = new ContextBudget(p, run, true);
        var overview = new UserMessage("概览".repeat(120));
        var answer = new UserMessage("初稿".repeat(120));
        var report = new UserMessage("报告".repeat(120));
        var evidence = new UserMessage("原文".repeat(120));
        assertTrue(revision.fits(List.of(overview), false));
        assertTrue(budget.fits(List.of(overview, answer, report, evidence), false));
        assertFalse(revision.fits(List.of(overview, answer, report, evidence), false));
        assertEquals(1500, revision.tokenLimit());
    }
}
