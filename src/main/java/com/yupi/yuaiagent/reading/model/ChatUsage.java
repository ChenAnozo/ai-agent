package com.yupi.yuaiagent.reading.model;

import com.yupi.yuaiagent.reading.research.ResearchRun;

import com.fasterxml.jackson.databind.*;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.*;

/**
 * Missing provider details remain unknown, including historical records.
 */
public final class ChatUsage {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void unknown(ResearchRun run, String phase) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        for (String key : List.of("promptTokens", "completionTokens", "totalTokens", "cacheHitTokens", "cacheMissTokens", "reasoningTokens"))
            row.put(key, null);
        row.put("finishReason", "API_FAILURE");
        run.event("usage", row);
    }

    public static void record(ResearchRun run, ChatResponse response, String phase) {
        var usage = response.getMetadata().getUsage();
        Map<String, Object> row = details(JSON.valueToTree(usage), usage.getPromptTokens());
        row.put("phase", phase);
        row.put("promptTokens", usage.getPromptTokens());
        row.put("completionTokens", usage.getCompletionTokens());
        row.put("totalTokens", usage.getTotalTokens());
        row.put("finishReason", response.getResult() == null ? null : response.getResult().getMetadata().getFinishReason());
        run.event("usage", row);
    }

    public static Map<String, Object> details(JsonNode usage, Integer input) {
        JsonNode nativeUsage = usage.has("nativeUsage") ? usage.path("nativeUsage") : usage;
        Long hit = count(nativeUsage.path("prompt_tokens_details").path("cached_tokens"));
        if (hit == null) hit = count(nativeUsage.path("prompt_cache_hit_tokens"));
        if (input == null || hit != null && hit > input) hit = null;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cacheHitTokens", hit);
        Long miss = count(nativeUsage.path("prompt_cache_miss_tokens"));
        if (miss != null && input != null && miss > input) miss = null;
        if (hit != null) miss = Long.valueOf(input.longValue() - hit);
        row.put("cacheMissTokens", miss);
        row.put("reasoningTokens", count(nativeUsage.path("completion_tokens_details").path("reasoning_tokens")));
        return row;
    }

    private static Long count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }
}
