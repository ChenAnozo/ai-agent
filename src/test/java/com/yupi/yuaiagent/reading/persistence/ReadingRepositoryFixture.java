package com.yupi.yuaiagent.reading.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.reading.config.ReadingMapperConfiguration;
import com.yupi.yuaiagent.reading.persistence.mapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One mapper factory per isolated test fixture; never performs startup recovery.
 */
public class ReadingRepositoryFixture {
    public final SqlSessionTemplate template;
    public final TransactionTemplate transactions;
    public final ReadingStore store;
    public final ReadingBookStore books;

    public ReadingRepositoryFixture(JdbcTemplate jdbc) {
        template = new SqlSessionTemplate(ReadingMapperConfiguration.factory(jdbc.getDataSource()));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var json = new ObjectMapper();
        store = new ReadingStore(json, transactions, template.getMapper(ReadingSessionMapper.class), template.getMapper(ReadingRunMapper.class),
                template.getMapper(ReadingKbMapper.class), template.getMapper(ReadingHistorySummaryMapper.class), template.getMapper(ReadingVectorMapper.class));
        books = new ReadingBookStore(template.getMapper(ReadingBookDocumentMapper.class), template.getMapper(ReadingBookChunkMapper.class),
                template.getMapper(ReadingKbMapper.class), transactions);
    }
}
