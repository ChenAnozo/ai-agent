package com.yupi.yuaiagent.reading.persistence.mapper;

import lombok.Data;

/**
 * Composite key (kbId, generation, vectorId); no single-ID CRUD.
 */
@Data
public class ReadingBookChunkEntity {
    private String kbId, generation, vectorId;
    private Integer ordinal, startPos, endPos;
}
