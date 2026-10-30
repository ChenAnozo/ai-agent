package com.yupi.yuaiagent.reading.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryTokenCounterTest {
    HistoryTokenCounter counter = new HistoryTokenCounter();
    @Test void countsMultilingualTextWithoutSpecialTokenInterpretation() {
        assertEquals(0, counter.count(""));
        assertTrue(counter.count("中文历史与 English context <|endoftext|> 😀") > 0);
    }
    @Test void boundedFallbackPreservesUnicodeAndNeverExceedsBudget() {
        String original = "中文 mixed text😀🧪引用与边界\n".repeat(20);
        assertEquals(original, counter.fit(original, counter.count(original)));
        for (int limit : new int[]{0, 1, 3, 10, 50}) {
            String fitted = counter.fit(original, limit);
            assertTrue(original.startsWith(fitted)); assertTrue(counter.count(fitted) <= limit);
            assertFalse(!fitted.isEmpty() && Character.isHighSurrogate(fitted.charAt(fitted.length() - 1)));
        }
    }
}
