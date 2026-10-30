package com.yupi.yuaiagent.reading.model;

import com.yupi.yuaiagent.reading.config.ReadingProperties;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.ResponseFormat;

import static org.junit.jupiter.api.Assertions.*;

class StructuredOutputTest {
    @Test
    void strictParsingRejectsAmbiguousOrIncompleteResponses() {
        var output = new StructuredOutput(new ReadingProperties());
        assertEquals("x", output.parse("```json\n{\"answerSkillId\":\"x\"}\n```").path("answerSkillId").asText());
        assertThrows(IllegalArgumentException.class, () -> output.parse("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> output.parse("{\"x\":1,\"x\":2}"));
        assertThrows(IllegalArgumentException.class, () -> output.parse("{\"x\":"));
    }

    @Test
    void sharedOutputModesStillConfigureReportsAndSkillRouting() {
        var p = new ReadingProperties();
        var output = new StructuredOutput(p);
        assertEquals(ResponseFormat.Type.JSON_OBJECT, output.options("report", "{}").getResponseFormat().getType());
        p.setPlannerOutputMode("PROMPT");
        assertNull(output.options("report", "{}").getResponseFormat());
        p.setPlannerOutputMode("JSON_SCHEMA");
        assertEquals(ResponseFormat.Type.JSON_SCHEMA, output.options("report", "{}").getResponseFormat().getType());
    }
}
