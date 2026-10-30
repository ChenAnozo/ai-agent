package com.yupi.yuaiagent.reading.research;

import java.util.Map;

/** Immutable configuration captured once before a research request starts. */
public record ResearchTemplate(String id, String name, String description, String engine, int version,
                               String baseId, int baseVersion, boolean official, Map<String, String> sections) {
    public ResearchTemplate {
        if (sections == null || sections.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
            throw new IllegalArgumentException("模板八项指导文字不能缺失");
        sections = Map.copyOf(sections);
    }

    public String section(String key) { return sections.get(key); }
    public boolean legacy() { return engine.equals("LEGACY_V2"); }
    public String protocol() { return legacy() ? "output-review-basic-v2" : "output-review-v5"; }
}
