package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName(value = "reading_history_summary", autoResultMap = true)
public class ReadingHistorySummaryEntity {
    @TableId(type = IdType.INPUT)
    private String sessionId;
    private String summary, coveredRunId, pendingRunId;
    private Integer pendingOffset;
}
