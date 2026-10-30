package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.*;

import static com.yupi.yuaiagent.reading.api.ReadingTypes.*;

@Data
@TableName(value = "reading_run", autoResultMap = true)
public class ReadingRunEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String sessionId;
    private String question;
    private String answer;
    private String status;
    @TableField(typeHandler = JsonbHandlers.SourcesHandler.class)
    private List<Source> sources;
    @TableField(typeHandler = JsonbHandlers.EventsHandler.class)
    private List<Event> events;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;
}
