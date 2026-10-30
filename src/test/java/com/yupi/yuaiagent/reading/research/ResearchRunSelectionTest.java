package com.yupi.yuaiagent.reading.research;

import com.yupi.yuaiagent.reading.api.ReadingTypes.*;
import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.*;

import static com.yupi.yuaiagent.reading.research.ReadingToolResults.InterestPoint;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchRunSelectionTest {
    ReadingStore store = mock(ReadingStore.class);
    ReadingProperties p = new ReadingProperties();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    BlockingQueue<Map<?, ?>> pending = new LinkedBlockingQueue<>();
    ResearchRun run;

    @BeforeEach void setup() {
        doAnswer(invocation -> {
            if (invocation.getArgument(2).equals("WAITING_FOR_SELECTION")) {
                List<Event> events = invocation.getArgument(4);
                pending.add((Map<?, ?>) events.getLast().data());
            }
            return null;
        }).when(store).checkpoint(anyString(), anyString(), anyString(), anyList(), anyList());
    }

    ResearchRun run() {
        return run = new ResearchRun("run", "session", new Settings(List.of(), "collaborative-research", "", "MANUAL"),
                new Mode("collaborative-research", "研究", "", ""), store, mock(SseEmitter.class), p);
    }

    List<InterestPoint> candidates(int round) {
        return java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(i -> new InterestPoint("R" + round + "-I" + i,
                "原句" + i, "理由", "问题" + i, "", "关键词" + i)).toList();
    }

    @AfterEach void close() { if (run != null) run.cancel(); worker.shutdownNow(); }

    @Test void selectionValidatesOwnershipAndIsIdempotentAcrossRounds() throws Exception {
        var run = run();
        run.event("research_draft", Map.of("answer", "已保存初稿"));
        for (int round = 1; round <= 2; round++) {
            int current = round;
            var future = worker.submit(() -> run.awaitInterestChoice(candidates(current), current));
            var request = pending.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            String id = request.get("selectionId").toString();
            assertFalse(future.isDone());
            assertThrows(ResponseStatusException.class, () -> run.confirmInterests(new InterestSelectionRequest("stale", List.of("R1-I1", "R1-I2"))));
            assertThrows(IllegalArgumentException.class, () -> run.confirmInterests(new InterestSelectionRequest(id, List.of("foreign-1", "foreign-2"))));
            var choice = new InterestSelectionRequest(id, List.of("R" + round + "-I2", "R" + round + "-I4"));
            run.confirmInterests(choice);
            run.confirmInterests(choice);
            assertEquals(choice.selectedIds(), future.get(2, TimeUnit.SECONDS).stream().map(InterestPoint::id).toList());
            assertThrows(ResponseStatusException.class, () -> run.confirmInterests(new InterestSelectionRequest(id, List.of("R" + current + "-I1", "R" + current + "-I2"))));
        }
        verify(store, atLeastOnce()).checkpoint(eq("run"), eq("已保存初稿"), eq("WAITING_FOR_SELECTION"), anyList(), anyList());
        assertEquals(2, run.events.stream().filter(e -> e.type().equals("interest_choice")).count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stopAndShutdownReleaseWaitingWorker(boolean shutdown) throws Exception {
        var run = run();
        var future = worker.submit(() -> run.awaitInterestChoice(candidates(1), 1));
        assertNotNull(pending.poll(2, TimeUnit.SECONDS));
        if (shutdown) run.interrupt(); else run.cancel();
        var error = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertEquals(shutdown ? "INTERRUPTED" : "CANCELLED", ((ResearchRun.Stopped) error.getCause()).status);
    }
}
