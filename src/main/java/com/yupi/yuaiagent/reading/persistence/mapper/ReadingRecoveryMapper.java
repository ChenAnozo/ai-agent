package com.yupi.yuaiagent.reading.persistence.mapper;

import org.apache.ibatis.annotations.Update;

public interface ReadingRecoveryMapper {
    @Update("UPDATE reading_kb SET status='FAILED',error='服务中断，请重试' WHERE status IN ('PENDING','PARSING','EMBEDDING')")
    int interruptKnowledge();

    @Update("UPDATE reading_run SET status='INTERRUPTED',answer=answer || E'\\n服务中断，本次回答未完成。' WHERE status IN ('RUNNING','WAITING_FOR_SELECTION')")
    int interruptRuns();

}
