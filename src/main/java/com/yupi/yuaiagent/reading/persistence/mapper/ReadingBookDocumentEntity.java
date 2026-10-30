package com.yupi.yuaiagent.reading.persistence.mapper;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Composite key (kbId, generation); use explicit Mapper operations.
 */
@Data
public class ReadingBookDocumentEntity {
    private String kbId, generation, textContent;
    private OffsetDateTime createdAt;
}
