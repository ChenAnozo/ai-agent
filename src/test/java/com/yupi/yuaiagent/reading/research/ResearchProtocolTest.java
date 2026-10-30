package com.yupi.yuaiagent.reading.research;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResearchProtocolTest {
    @Test
    void identicalRepeatedActionsAreNormalizedWithoutChangingData() {
        var parsed = ResearchProtocol.parse("{\"action\":\"FINISH\",\"claims\":[],\"action\":\"FINISH\"}");
        assertEquals("FINISH", parsed.value().path("action").asText());
        assertEquals(2, parsed.value().size());
        assertEquals(List.of("$.action"), parsed.normalizedPaths());
    }

    @Test
    void conflictingActionsAreNeverSilentlyOverwritten() {
        var error = assertThrows(IllegalArgumentException.class, () -> ResearchProtocol.parse("{\"action\":\"INVESTIGATE\",\"action\":\"FINISH\"}"));
        assertTrue(error.getMessage().contains("重复字段值冲突：$.action"));
    }

    @Test
    void nestedConflictingSourcesAreRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ResearchProtocol.parse("{\"claims\":[{\"sourceIds\":[\"S1\"],\"sourceIds\":[\"S2\"]}]}")).getMessage().contains("$.claims[0].sourceIds"));
    }

    @Test
    void normalizationDoesNotChangeStringValuesOrClaimCounts() {
        var parsed = ResearchProtocol.parse("{\"claims\":[{\"statement\":\"原文中的action不改\",\"statement\":\"原文中的action不改\"},{\"id\":\"C2\"}]}");
        assertEquals(2, parsed.value().path("claims").size());
        assertEquals("原文中的action不改", parsed.value().path("claims").get(0).path("statement").asText());
    }

    @Test
    void malformedTruncatedAndTrailingObjectsRemainInvalid() {
        assertThrows(IllegalArgumentException.class, () -> ResearchProtocol.parse("{\"claims\":["));
        assertThrows(IllegalArgumentException.class, () -> ResearchProtocol.parse("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> ResearchProtocol.parse("not-json"));
    }

    @Test
    void fencesAndScalarTypesRemainSupported() {
        var parsed = ResearchProtocol.parse("```json\n{\"flag\":true,\"no\":false,\"empty\":null,\"i\":2,\"d\":1.5}\n```");
        assertTrue(parsed.value().path("flag").asBoolean());
        assertEquals(1.5, parsed.value().path("d").asDouble());
        assertTrue(parsed.normalizedPaths().isEmpty());
    }
}
