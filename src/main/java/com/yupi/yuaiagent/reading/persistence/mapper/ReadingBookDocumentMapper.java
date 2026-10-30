package com.yupi.yuaiagent.reading.persistence.mapper;

import org.apache.ibatis.annotations.*;

public interface ReadingBookDocumentMapper {
    @Select("SELECT * FROM reading_book_document WHERE kb_id=#{kbId} AND generation=#{generation}")
    ReadingBookDocumentEntity find(@Param("kbId") String kbId, @Param("generation") String generation);

    @Insert("INSERT INTO reading_book_document(kb_id,generation,text_content) VALUES(#{kbId},#{generation},#{textContent}) ON CONFLICT(kb_id,generation) DO NOTHING")
    int insertIfAbsent(ReadingBookDocumentEntity document);

}
