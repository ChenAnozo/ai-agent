package com.yupi.yuaiagent.reading.research;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResearchDecisionTest {
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETE", "CONTINUE"})
    void acceptsOnlyOneDecisionField(String value) {
        assertEquals(Map.of("decision", value), ResearchDecision.parse("{\"decision\":\"" + value + "\"}"));
    }

    @Test
    void acceptsFencedControlWithoutChangingItsMeaning() {
        assertEquals(Map.of("decision", "CONTINUE"), ResearchDecision.parse("```json\n{\"decision\":\"CONTINUE\"}\n```"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "COMPLETE", "{\"decision\":null}", "{\"decision\":true}",
            "{\"decision\":\"ABORT\"}", "{\"decision\":\"FINISH\"}", "{\"decision\":\" COMPLETE \"}",
            "{\"decision\":\"COMPLETE\",\"reason\":\"不需要\"}", "{\"action\":\"FINISH\",\"stopReason\":\"RESPONSE_READY\",\"reason\":\"旧协议\"}",
            "{\"decision\":\"CONTINUE\",\"nextInvestigation\":\"不需要\"}",
            "{\"decision\":\"COMPLETE\",\"decision\":\"COMPLETE\"}", "{\"decision\":\"COMPLETE\",\"decision\":\"CONTINUE\"}",
            "{\"decision\":\"COMPLETE\"}尾部说明", "{\"decision\":"})
    void rejectsAmbiguousLegacyOrMalformedControls(String raw) {
        assertThrows(IllegalArgumentException.class, () -> ResearchDecision.parse(raw));
    }
}
