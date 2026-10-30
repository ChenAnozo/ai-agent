package com.yupi.yuaiagent.reading.research;


import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/**
 * Normalize identical repeated fields only; never overwrite conflicting values or invent missing data.
 */
final class ResearchProtocol {
    record Parsed(JsonNode value, List<String> normalizedPaths) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    static Parsed parse(String raw) {
        String text = raw.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        var normalized = new LinkedHashSet<String>();
        try (var parser = JSON.getFactory().createParser(text)) {
            if (parser.nextToken() == null) throw new IllegalArgumentException("模型返回空的公开输出");
            JsonNode value = read(parser, "$", normalized);
            if (parser.nextToken() != null) throw new IllegalArgumentException("JSON对象后存在额外内容");
            return new Parsed(value, List.copyOf(normalized));
        } catch (JsonProcessingException e) {
            var at = e.getLocation();
            throw new IllegalArgumentException("JSON语法错误，第" + at.getLineNr() + "行、第" + at.getColumnNr() + "列：" + e.getOriginalMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法读取模型公开JSON", e);
        }
    }

    private static JsonNode read(JsonParser parser, String path, Set<String> normalized) throws java.io.IOException {
        var factory = JsonNodeFactory.instance;
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                ObjectNode object = factory.objectNode();
                for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken()) {
                    if (token != JsonToken.FIELD_NAME) throw new IllegalArgumentException("JSON对象未完整结束：" + path);
                    String name = parser.currentName();
                    String field = path + "." + name;
                    if (parser.nextToken() == null) throw new IllegalArgumentException("字段值缺失：" + field);
                    JsonNode value = read(parser, field, normalized);
                    if (object.has(name)) {
                        if (!object.get(name).equals(value))
                            throw new IllegalArgumentException("重复字段值冲突：" + field + "；每个字段只能出现一次，请明确保留正确值");
                        normalized.add(field);
                    } else object.set(name, value);
                }
                yield object;
            }
            case START_ARRAY -> {
                ArrayNode array = factory.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() == null) throw new IllegalArgumentException("JSON数组未完整结束：" + path);
                    array.add(read(parser, path + "[" + array.size() + "]", normalized));
                }
                yield array;
            }
            case VALUE_STRING -> factory.textNode(parser.getText());
            case VALUE_NUMBER_INT -> factory.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> factory.numberNode(parser.getDecimalValue());
            case VALUE_TRUE -> factory.booleanNode(true);
            case VALUE_FALSE -> factory.booleanNode(false);
            case VALUE_NULL -> factory.nullNode();
            default -> throw new IllegalArgumentException("JSON值无效：" + path);
        };
    }
}
