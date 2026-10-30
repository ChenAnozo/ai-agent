package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "reading_kb", autoResultMap = true)
public class ReadingKbEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name, filename, format, path, status, error, modelId, generation;
    private Integer chunkCount, dimensions;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private java.time.OffsetDateTime createdAt;
}
