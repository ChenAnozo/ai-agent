package com.yupi.yuaiagent.reading.config;


import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "reading.enabled", havingValue = "true")
@EnableConfigurationProperties(ReadingProperties.class)
public class ReadingConfiguration {
    @Bean
    JdbcTemplate readingJdbc(ReadingProperties p) {
        if (p.getChunkSize() <= 0 || p.getChunkOverlap() < 0 || p.getChunkOverlap() >= p.getChunkSize()
                || p.getTopK() <= 0 || p.getMaxSteps() <= 0 || p.getMaxToolCalls() <= 0
                || p.getCollaborationMaxInvestigations() < 1 || p.getCollaborationMaxInvestigations() > 10
                || p.getInvestigatorMaxCalls() < 2 || p.getInvestigatorMaxCalls() > 10
                || p.getHistorySummaryTargetTokens() < 64 || p.getHistorySummaryThresholdTokens() <= p.getHistorySummaryTargetTokens()
                || p.getHistorySummaryBatchTokens() < 128 || p.getHistorySummaryMaxCalls() < 0
                || p.getHistorySummaryOutputTokens() < p.getHistorySummaryTargetTokens() || p.getHistorySummaryOutputTokens() > 32768
                || p.getMaxHistoryMessages() < 2 || p.getMaxContextCharacters() < 2000
                || p.getMaxFullTextCharacters() <= 0 || p.getMaxFullTextContextCharacters() <= p.getMaxFullTextCharacters()
                || p.getReadCharacters() < 1 || p.getReadCharacters() > 12000 || p.getHybridCandidates() < 1
                || !java.util.Set.of("JSON_OBJECT", "JSON_SCHEMA", "PROMPT").contains(p.getPlannerOutputMode())
                || p.getContextOutputTokens() < 1024 || p.getContextOutputTokens() > 32768
                || p.getContextInputTokens() < 0 || p.getFullContextInputTokens() < 0 || p.getSynthesisReserveTokens() < 512
                || p.getPlannerDiagnosticCharacters() < 1000 || p.getPlannerDiagnosticCharacters() > 32000)
            throw new IllegalArgumentException("reading 配置范围无效");
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(p.getDatabaseUrl());
        ds.setUser(p.getDatabaseUser());
        ds.setPassword(p.getDatabasePassword());
        ds.setCurrentSchema("reading,public");
        ds.setConnectTimeout(10);
        ds.setSocketTimeout(30);
        Flyway.configure().dataSource(ds).schemas("reading").defaultSchema("reading")
                .locations("classpath:db/reading").table("reading_schema_history").load().migrate();
        return new JdbcTemplate(ds);
    }

    @Bean
    TransactionTemplate readingTransactions(JdbcTemplate readingJdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(readingJdbc.getDataSource()));
    }

    @Bean
    @org.springframework.context.annotation.DependsOn("readingStartupRecovery")
    PgVectorStore readingVectors(JdbcTemplate readingJdbc, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(readingJdbc, embeddingModel).vectorTableName("reading_vectors")
                .schemaName("reading")
                .initializeSchema(false).indexType(PgVectorStore.PgIndexType.NONE)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE).build();
    }

}
