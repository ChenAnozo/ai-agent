package com.yupi.yuaiagent.reading.api;


import java.util.List;

public final class ReadingTypes {
    private ReadingTypes() {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Settings(List<String> knowledgeBaseIds, String modeId, String researchTemplateId, String interestSelectionMode) {
        public Settings(List<String> knowledgeBaseIds, String modeId, String researchTemplateId) {
            this(knowledgeBaseIds, modeId, researchTemplateId, "AUTO");
        }

        public Settings(List<String> knowledgeBaseIds, String modeId, boolean ignoredWeb) {
            this(knowledgeBaseIds, modeId, "", "AUTO");
        }

        public Settings {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds.stream().distinct().toList();
            modeId = modeId == null ? "" : modeId;
            researchTemplateId = researchTemplateId == null ? "" : researchTemplateId;
            if (!researchTemplateId.isEmpty() && !researchTemplateId.matches("[a-zA-Z0-9_-]{1,80}"))
                throw new IllegalArgumentException("研究模板 ID 无效");
            interestSelectionMode = interestSelectionMode == null || interestSelectionMode.isBlank() ? "AUTO" : interestSelectionMode;
            if (!java.util.Set.of("AUTO", "MANUAL").contains(interestSelectionMode))
                throw new IllegalArgumentException("兴趣点选择模式无效");
            if (knowledgeBaseIds.size() > 30)
                throw new IllegalArgumentException("最多选择 30 个知识库");
            knowledgeBaseIds.forEach(java.util.UUID::fromString);
        }
    }

    public record InterestSelectionRequest(String selectionId, List<String> selectedIds) {
        public InterestSelectionRequest {
            if (selectionId == null || selectionId.isBlank() || selectedIds == null || selectedIds.size() != 2
                    || selectedIds.stream().anyMatch(java.util.Objects::isNull) || selectedIds.stream().distinct().count() != 2)
                throw new IllegalArgumentException("请提交选择请求编号和两个不同的兴趣点ID");
            selectedIds = List.copyOf(selectedIds);
        }
    }

    // Offsets are UTF-16 indices in the full text, with an exclusive end.
    public record Chunk(String text, int start, int end, int ordinal) {
        public String location() {
            return "原文字符 " + start + "–" + end;
        }
    }

    public record Mode(String id, String name, String description, String content) {
    }

    public record Source(String id, String type, String title, String location, String url, String text) {
    }

    public record Event(String type, Object data) {
    }

    public record MessageRequest(String message, Settings settings) {
    }

    public record NamedText(String name, String content) {
    }
}
