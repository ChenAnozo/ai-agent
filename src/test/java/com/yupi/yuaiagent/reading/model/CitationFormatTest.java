package com.yupi.yuaiagent.reading.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CitationFormatTest {
    @Test
    void normalizesWhitespaceInsideSourceMarkersWithoutChangingSentences() {
        assertEquals("原句[S11]与条件[S15][S7]。", CitationFormat.normalize("原句[ S11 ]与条件[\tS15\u3000][S7]。"));
        assertEquals("[S11][S15]", CitationFormat.normalize("[S11][S15]"));
    }

    @Test
    void retainsLocationAnnotationsAndDoesNotTreatArbitraryBracketedTextAsCitations() {
        assertEquals("原文[S11]（字符100–200）", CitationFormat.normalize("原文（S11，字符100–200）"));
        assertEquals("[ S15 words ] [Section15]", CitationFormat.normalize("[ S15 words ] [Section15]"));
    }

    @Test
    void escapedCitationBracketsRemainRecognizableForValidationAndRemoval() {
        assertEquals("原句[S1][S15]。", CitationFormat.normalize("原句\\[S1\\]\\[ S15 \\]。"));
        assertEquals("[S1][S15]", CitationFormat.normalize("\\[S1][S15\\]"));
        assertEquals("\\*保留Markdown转义\\*", CitationFormat.normalize("\\*保留Markdown转义\\*"));
    }
}
