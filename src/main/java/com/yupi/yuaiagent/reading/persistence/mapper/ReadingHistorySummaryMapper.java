package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface ReadingHistorySummaryMapper extends BaseMapper<ReadingHistorySummaryEntity> {
    @Insert("INSERT INTO reading_history_summary(session_id,summary,covered_run_id,pending_run_id,pending_offset) "
            + "VALUES(#{sessionId},#{summary},#{coveredRunId},#{pendingRunId},#{pendingOffset}) "
            + "ON CONFLICT(session_id) DO UPDATE SET summary=excluded.summary,covered_run_id=excluded.covered_run_id,"
            + "pending_run_id=excluded.pending_run_id,pending_offset=excluded.pending_offset")
    int upsert(ReadingHistorySummaryEntity summary);
}
