package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

import com.yupi.yuaiagent.reading.api.ReadingTypes.Settings;

@Data
@TableName(value = "reading_session", autoResultMap = true)
public class ReadingSessionEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    @TableField(typeHandler = JsonbHandlers.SettingsHandler.class)
    private Settings settings;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;
}
