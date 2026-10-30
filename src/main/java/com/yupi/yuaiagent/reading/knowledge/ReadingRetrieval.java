package com.yupi.yuaiagent.reading.knowledge;

import com.yupi.yuaiagent.reading.config.ReadingProperties;
import com.yupi.yuaiagent.reading.persistence.ReadingStore;
import com.yupi.yuaiagent.reading.persistence.mapper.ReadingKbEntity;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

@Service
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ReadingRetrieval {
    private final ReadingStore store;
    private final ReadingProperties p;

    //向量模型
    private final EmbeddingModel embedding;

    public ReadingRetrieval(ReadingStore store, ReadingProperties p, EmbeddingModel embeddingModel) {
        this.store = store;
        this.p = p;
        this.embedding = embeddingModel;
    }

    public List<ReadingKbEntity> validate(List<String> ids) {
        if (ids.isEmpty()) throw new IllegalArgumentException("至少选择一个已入库的知识库");
        var kbs = ids.stream().map(store::knowledgeBase).toList();
        for (var kb : kbs) {
            if (!"READY".equals(kb.getStatus())) throw new IllegalArgumentException("知识库尚未就绪：" + kb.getName());
            if (!p.getEmbeddingModelId().equals(kb.getModelId()))
                throw new IllegalArgumentException("向量模型已更换，请重建知识库：" + kb.getName());
        }
        if (kbs.stream().map(k -> k.getDimensions()).distinct().count() != 1)
            throw new IllegalArgumentException("知识库向量维度不一致，请重建");
        return kbs;
    }

    //ids是知识库id，因为可以选择多个知识库
    public List<Source> search(List<String> ids, String query) {
        ReadingStore.required(query, 2000);
        var kbs = validate(ids);
        float[] queryVector = embedding.embed(query);
        int dimensions = queryVector.length;
        if (kbs.stream().anyMatch(k -> k.getDimensions() != dimensions))
            throw new IllegalArgumentException("当前向量模型维度不匹配，请重建知识库");
        return store.vectorSearch(ids, p.getEmbeddingModelId(), dimensions, queryVector, p.getTopK());
    }
}
