package com.yupi.yuaiagent.reading.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface ReadingKbMapper extends BaseMapper<ReadingKbEntity> {
    @org.apache.ibatis.annotations.Select("SELECT generation,status FROM reading_kb WHERE id=#{id} FOR UPDATE")
    ReadingKbEntity lockVersion(@org.apache.ibatis.annotations.Param("id") String id);
}
