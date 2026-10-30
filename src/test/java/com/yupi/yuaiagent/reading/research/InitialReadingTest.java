package com.yupi.yuaiagent.reading.research;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitialReadingTest {
    private String envelope(String draft, String overview) {
        return draft + "\n" + InitialReading.OVERVIEW_MARKER + "\n" + overview;
    }

    @Test
    void preservesMarkdownQuotesBackslashesNewlinesAndCode() {
        String draft = "## 初稿\n\n原句：\"条件\"。\\路径\\[S1\\]\n\\(x\\)\\alpha\n- 解释机制";
        String overview = "## 概览\n条件\t反例\n```text\n{\"key\": \"value\"}\n```";
        var parsed = InitialReading.parseWithDiagnostics(envelope(draft, overview));
        assertEquals(draft, parsed.reading().draft());
        assertEquals(overview, parsed.reading().bookOverview());
        assertTrue(parsed.normalizations().isEmpty());
    }

    @Test
    void acceptsCrLfAndHorizontalWhitespaceAroundDelimiter() {
        var result = InitialReading.parse("初稿\r\n第二段\r\n \t<<<BOOK_OVERVIEW>>> \t\r\n概览\r\n反例");
        assertEquals("初稿\r\n第二段", result.draft());
        assertEquals("概览\r\n反例", result.bookOverview());
    }

    @Test
    void inlineMarkerIsContentRatherThanBoundary() {
        String draft = "正文提及 <<<BOOK_OVERVIEW>>>，仍为正文。";
        assertEquals(draft, InitialReading.parse(envelope(draft, "概览")).draft());
        assertThrows(IllegalArgumentException.class, () -> InitialReading.parse(draft));
    }

    @Test
    void rejectsMissingRepeatedOrEmptyPartsAndLegacyJson() {
        for (String raw : new String[]{"", "只有初稿", "<<<BOOK_OVERVIEW>>>\n概览",
                "初稿\n<<<BOOK_OVERVIEW>>>", envelope("初稿", "概览") + "\n<<<BOOK_OVERVIEW>>>\n其他",
                "初稿 <<<BOOK_OVERVIEW>>> 概览", "{\"draft\":\"初稿\",\"bookOverview\":\"概览\"}"})
            assertThrows(IllegalArgumentException.class, () -> InitialReading.parse(raw), raw);
        assertThrows(IllegalArgumentException.class, () -> InitialReading.parse(null));
    }

    @Test
    void handlesBomWithoutChangingDocuments() {
        var result = InitialReading.parseWithDiagnostics("\uFEFF" + envelope("初稿", "概览"));
        assertEquals("初稿", result.reading().draft());
        assertEquals(1, result.normalizations().size());
    }

    @Test
    void enforcesIndividualLengthLimitWithoutEnforcingRatio() {
        assertEquals("概览".repeat(100), InitialReading.parse(envelope("初稿", "概览".repeat(100))).bookOverview());
        assertEquals(60000, InitialReading.parse(envelope("a".repeat(60000), "概览")).draft().length());
        assertThrows(IllegalArgumentException.class, () -> InitialReading.parse(envelope("a".repeat(60001), "概览")));
        assertThrows(IllegalArgumentException.class, () -> InitialReading.parse(envelope("初稿", "a".repeat(60001))));
        assertThrows(IllegalArgumentException.class, () -> InitialReading.parse(envelope("初稿\u0000", "概览")));
    }
}
