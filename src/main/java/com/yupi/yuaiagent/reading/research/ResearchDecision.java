package com.yupi.yuaiagent.reading.research;

import java.util.Map;
import java.util.Set;

/** The model judges research value; execution limits belong to the backend. */
final class ResearchDecision {
    static Map<String, Object> parse(String raw) {
        var parsed = ResearchProtocol.parse(raw);
        if (!parsed.normalizedPaths().isEmpty()) throw new IllegalArgumentException("控制协议不允许重复字段");
        var root = parsed.value();
        if (!root.isObject() || root.size() != 1 || !root.has("decision"))
            throw new IllegalArgumentException("控制协议必须且只能包含decision字段");
        var value = root.path("decision");
        if (!value.isTextual() || !Set.of("COMPLETE", "CONTINUE").contains(value.asText()))
            throw new IllegalArgumentException("decision只能为COMPLETE或CONTINUE");
        return Map.of("decision", value.asText());
    }
}
