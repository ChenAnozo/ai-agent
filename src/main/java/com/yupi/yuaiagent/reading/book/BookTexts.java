package com.yupi.yuaiagent.reading.book;

import com.yupi.yuaiagent.reading.persistence.ReadingBookStore;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.persistence.mapper.ReadingBookChunkEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/** Full text and passage positions published by knowledge ingestion. */
@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class BookTexts {
    public record Book(String id, String name, String generation, String text, List<ReadingBookChunkEntity> chunks) {}
    private final ReadingStore store;
    private final ReadingBookStore texts;

    public BookTexts(ReadingStore store, ReadingBookStore texts) {
        this.store = store;
        this.texts = texts;
    }

    public Book load(String id) {
        var kb = store.knowledgeBase(id);
        if (!"READY".equals(kb.getStatus()))
            throw new IllegalArgumentException("知识库正在重建或尚未就绪");
        var document = texts.document(id, kb.getGeneration());
        var chunks = texts.chunks(id, kb.getGeneration());
        if (document == null || chunks.isEmpty() || chunks.size() != kb.getChunkCount())
            throw new IllegalArgumentException("知识库缺少完整原文或片段位置，请重新入库：" + kb.getName());
        return new Book(id, kb.getName(), kb.getGeneration(), document.getTextContent(), chunks);
    }
}
