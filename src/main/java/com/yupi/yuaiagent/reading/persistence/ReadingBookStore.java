package com.yupi.yuaiagent.reading.persistence;

import com.yupi.yuaiagent.reading.api.ReadingTypes.Chunk;
import com.yupi.yuaiagent.reading.persistence.mapper.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Persists versioned full text and passage positions during ingestion. */
@Repository
@org.springframework.context.annotation.DependsOn("readingStartupRecovery")
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
public class ReadingBookStore {
    private final ReadingBookDocumentMapper documents;
    private final ReadingBookChunkMapper chunks;
    private final ReadingKbMapper knowledge;
    private final TransactionTemplate transactions;

    public ReadingBookStore(ReadingBookDocumentMapper documents, ReadingBookChunkMapper chunks,
                            ReadingKbMapper knowledge, @Qualifier("readingTransactions") TransactionTemplate transactions) {
        this.documents = documents;
        this.chunks = chunks;
        this.knowledge = knowledge;
        this.transactions = transactions;
    }

    public ReadingBookDocumentEntity document(String kbId, String generation) {
        return documents.find(kbId, generation);
    }

    public List<ReadingBookChunkEntity> chunks(String kbId, String generation) {
        return chunks.list(kbId, generation);
    }

    public void saveText(String kbId, String generation, String text, List<Chunk> positions, List<String> vectorIds) {
        if (vectorIds.size() != positions.size() || positions.isEmpty())
            throw new IllegalArgumentException("原文片段与向量数量不一致");
        for (int i = 0; i < positions.size(); i++) {
            var c = positions.get(i);
            if (c.ordinal() != i || c.start() < 0 || c.end() <= c.start() || c.end() > text.length()
                    || !text.substring(c.start(), c.end()).equals(c.text()))
                throw new IllegalArgumentException("原文与片段位置不一致");
        }
        transactions.executeWithoutResult(tx -> {
            var kb = knowledge.lockVersion(kbId);
            if (kb == null || !generation.equals(kb.getGeneration()) || !"EMBEDDING".equals(kb.getStatus()))
                throw new IllegalArgumentException("知识库版本已变化，请重新入库");
            var entity = new ReadingBookDocumentEntity();
            entity.setKbId(kbId);
            entity.setGeneration(generation);
            entity.setTextContent(text);
            documents.insertIfAbsent(entity);
            var existing = documents.find(kbId, generation);
            if (!text.equals(existing.getTextContent()))
                throw new IllegalArgumentException("同一知识库版本的原文已变化，请重新入库");
            chunks.deleteVersion(kbId, generation);
            for (int start = 0; start < positions.size(); start += 256) {
                List<ReadingBookChunkEntity> batch = new ArrayList<>();
                for (int i = start; i < Math.min(start + 256, positions.size()); i++) {
                    var position = positions.get(i);
                    var chunk = new ReadingBookChunkEntity();
                    chunk.setKbId(kbId);
                    chunk.setGeneration(generation);
                    chunk.setVectorId(vectorIds.get(i));
                    chunk.setOrdinal(position.ordinal());
                    chunk.setStartPos(position.start());
                    chunk.setEndPos(position.end());
                    batch.add(chunk);
                }
                chunks.insertBatch(batch);
            }
        });
    }
}
