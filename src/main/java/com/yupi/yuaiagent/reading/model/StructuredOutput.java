package com.yupi.yuaiagent.reading.model;

import com.yupi.yuaiagent.reading.config.ReadingProperties;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

/**
 * Shared structured-response configuration and strict JSON parsing.
 */
public final class StructuredOutput {
    private final ReadingProperties p;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public StructuredOutput(ReadingProperties p) {
        this.p = p;
    }

    public JsonNode parse(String raw) {
        String text = raw.strip().replaceFirst("^```(?:json)\\s*", "").replaceFirst("\\s*```$", "");
        try {
            JsonNode node = json.readTree(text);
            if (node == null) throw new IllegalArgumentException();
            return node;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析有效JSON，请返回完整对象且不附带说明文字", e);
        }
    }

    public OpenAiChatOptions options(String name, String outputSchema) {
        var builder = OpenAiChatOptions.builder().internalToolExecutionEnabled(false).maxTokens(4096);
        switch (p.getPlannerOutputMode()) {
            case "JSON_OBJECT" ->
                    builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
            case "JSON_SCHEMA" ->
                    builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA).jsonSchema(ResponseFormat.JsonSchema.builder().name(name).strict(true).schema(outputSchema).build()).build());
            case "PROMPT" -> {
            }
            default -> throw new IllegalArgumentException("planner-output-mode仅支持JSON_OBJECT、JSON_SCHEMA、PROMPT");
        }
        return builder.build();
    }
}
