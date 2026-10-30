package com.yupi.yuaiagent.reading.research;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Set;

/** Initial full-book/history input and a separate revision base without either. */
public record MainContext(List<Message> fixedMessages, List<Message> revisionBaseMessages, Set<String> fullTextSourceIds) {
    public MainContext {
        fixedMessages = List.copyOf(fixedMessages);
        revisionBaseMessages = List.copyOf(revisionBaseMessages);
        fullTextSourceIds = Set.copyOf(fullTextSourceIds);
    }
}
