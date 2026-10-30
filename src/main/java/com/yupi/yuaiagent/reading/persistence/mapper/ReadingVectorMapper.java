package com.yupi.yuaiagent.reading.persistence.mapper;

import com.yupi.yuaiagent.reading.api.ReadingTypes.Source;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ReadingVectorMapper {
    @Select("SELECT vector_dims(embedding) FROM reading_vectors WHERE metadata->>'kbId'=#{kbId} LIMIT 1")
    Integer dimensions(@Param("kbId") String kbId);

    @Delete("DELETE FROM reading_vectors WHERE metadata->>'kbId'=#{kbId}")
    int deleteKnowledge(@Param("kbId") String kbId);

    List<Source> search(@Param("ids") List<String> ids, @Param("modelId") String modelId, @Param("dimensions") int dimensions,
                        @Param("vector") String vector, @Param("limit") int limit);

    List<String> fullTextIds(@Param("kbId") String kbId, @Param("generation") String generation, @Param("vector") String vector, @Param("limit") int limit);
}
