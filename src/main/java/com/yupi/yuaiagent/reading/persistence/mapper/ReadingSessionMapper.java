package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

public interface ReadingSessionMapper extends BaseMapper<ReadingSessionEntity> {
    /**
     * Preserve the conditional title update atomically, without a read/update race.
     */
    @Update("UPDATE reading_session SET title=CASE WHEN title='新对话' THEN #{title} ELSE title END,updated_at=now() WHERE id=#{id}")
    int touchForRun(@Param("id") String id, @Param("title") String title);
}
