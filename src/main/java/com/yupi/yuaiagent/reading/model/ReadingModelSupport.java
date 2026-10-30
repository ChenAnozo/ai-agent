package com.yupi.yuaiagent.reading.model;


import java.util.Objects;

import org.springframework.ai.chat.model.ChatResponse;

/**
 * Shared model response extraction.
 */
public final class ReadingModelSupport {
    private ReadingModelSupport() {
    }

    public static String answerText(ChatResponse response) {
        String text = response.getResult() == null ? "" : Objects.toString(response.getResult().getOutput().getText(), "");
        if (text.isBlank())
            throw new IllegalStateException("模型没有返回回答正文，请检查输出Token额度；结束原因：" + (response.getResult() == null ? "UNKNOWN" : response.getResult().getMetadata().getFinishReason()));
        return text;
    }
}
