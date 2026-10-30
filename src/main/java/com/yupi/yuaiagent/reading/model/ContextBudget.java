package com.yupi.yuaiagent.reading.model;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.research.ResearchRun;

import org.springframework.ai.chat.messages.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Conservative UTF-8 estimate, NOT a provider tokenizer. Legacy character limits remain guards.
 */
public final class ContextBudget {
    private final ReadingProperties p;
    private final ResearchRun run;
    private final boolean local;

    public ContextBudget(ReadingProperties p, ResearchRun run) {
        this(p, run, false);
    }

    public ContextBudget(ReadingProperties p, ResearchRun run, boolean local) {
        this.p = p;
        this.run = run;
        this.local = local;
    }

    int characters(List<Message> messages) {
        return messages.stream().mapToInt(m -> {
            int n = Objects.toString(m.getText(), "").length();
            if (m instanceof ToolResponseMessage t)
                n += t.getResponses().stream().mapToInt(r -> r.responseData().length()).sum();
            if (m instanceof AssistantMessage a)
                n += a.getToolCalls().stream().mapToInt(t -> t.arguments().length()).sum();
            return n;
        }).sum();
    }

    public int estimate(String text) {
        return (int) Math.ceil(text.getBytes(StandardCharsets.UTF_8).length / 2.0 * 1.1);
    }

    public int tokens(List<Message> messages) {
        int n = messages.size() * 16;
        for (Message m : messages) {
            n += estimate(Objects.toString(m.getText(), ""));
            if (m instanceof ToolResponseMessage t) for (var r : t.getResponses()) n += estimate(r.responseData()) + 16;
            if (m instanceof AssistantMessage a) for (var t : a.getToolCalls()) n += estimate(t.arguments()) + 32;
        }
        return n;
    }

    public int characterLimit() {
        return !local && run.fullBookRead ? p.getMaxFullTextContextCharacters() : p.getMaxContextCharacters() + 32000;
    }

    public int tokenLimit() {
        int configured = !local && run.fullBookRead ? p.getFullContextInputTokens() : p.getContextInputTokens();
        return configured > 0 ? configured : characterLimit() * 2;
    }

    public int reservedTokens() {
        return p.isContextManagementEnabled() ? p.getSynthesisReserveTokens() : 0;
    }

    public boolean fits(List<Message> messages, boolean reserve) {
        return characters(messages) <= characterLimit() - (reserve ? reservedTokens() * 2 : 0) && tokens(messages) <= tokenLimit() - (reserve ? reservedTokens() : 0);
    }

    public void require(List<Message> messages, boolean reserve) {
        if (!fits(messages, reserve))
            throw new ResearchRun.Stopped("LIMIT_REACHED", "固定输入或原文展开超过上下文预算；全文不会自动截断，请减少书籍或调整输入预算");
    }

    public Map<String, Object> diagnostic(List<Message> messages, String action) {
        return Map.of("action", action, "characters", characters(messages), "characterLimit", characterLimit(), "estimatedTokens", tokens(messages), "inputTokenLimit", tokenLimit(), "reservedTokens", reservedTokens(), "outputTokens", p.getContextOutputTokens(), "counting", "UTF8_ESTIMATE_WITH_10_PERCENT_MARGIN");
    }
}
