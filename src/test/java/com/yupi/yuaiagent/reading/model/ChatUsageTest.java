package com.yupi.yuaiagent.reading.model;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatUsageTest {
    ObjectMapper json = new ObjectMapper();

    @Test
    void standardDetailsTakePriorityAndMissIsDerived() throws Exception {
        var row = ChatUsage.details(json.readTree("{\"nativeUsage\":{\"prompt_tokens_details\":{\"cached_tokens\":80},\"prompt_cache_hit_tokens\":90,\"completion_tokens_details\":{\"reasoning_tokens\":12}}}"), 100);
        assertEquals(80L, row.get("cacheHitTokens"));
        assertEquals(20L, row.get("cacheMissTokens"));
        assertEquals(12L, row.get("reasoningTokens"));
    }

    @Test
    void missingAndInvalidDetailsAreUnknownRatherThanZero() throws Exception {
        for (String data : new String[]{"{}", "{\"prompt_cache_hit_tokens\":101}", "{\"prompt_cache_hit_tokens\":-1}"}) {
            var row = ChatUsage.details(json.readTree(data), 100);
            assertNull(row.get("cacheHitTokens"));
            assertNull(row.get("cacheMissTokens"));
            assertNull(row.get("reasoningTokens"));
        }
        var zero = ChatUsage.details(json.readTree("{\"prompt_cache_hit_tokens\":0}"), 100);
        assertEquals(0L, zero.get("cacheHitTokens"));
        assertEquals(100L, zero.get("cacheMissTokens"));
    }
}
