package com.yupi.yuaiagent.reading.persistence.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ReadingBookChunkMapper {
    @Select("SELECT kb_id,generation,vector_id::text AS vector_id,ordinal,start_pos,end_pos FROM reading_book_chunk "
            + "WHERE kb_id=#{kbId} AND generation=#{generation} ORDER BY ordinal")
    List<ReadingBookChunkEntity> list(@Param("kbId") String kbId, @Param("generation") String generation);

    @Delete("DELETE FROM reading_book_chunk WHERE kb_id=#{kbId} AND generation=#{generation}")
    int deleteVersion(@Param("kbId") String kbId, @Param("generation") String generation);

    @Insert({"<script>INSERT INTO reading_book_chunk(kb_id,generation,vector_id,ordinal,start_pos,end_pos) VALUES",
            "<foreach collection='chunks' item='c' separator=','>",
            "(#{c.kbId},#{c.generation},CAST(#{c.vectorId} AS uuid),#{c.ordinal},#{c.startPos},#{c.endPos})",
            "</foreach></script>"})
    int insertBatch(@Param("chunks") List<ReadingBookChunkEntity> chunks);
}
