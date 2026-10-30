package com.yupi.yuaiagent.reading.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("reading")
public class ReadingProperties {
    private boolean enabled;
    private String databaseUrl, databaseUser, databasePassword;
    private String dataDirectory = ".reading-data", skillsDirectory = "research-skills";
    private String templatesDirectory = "research-templates";
    private int collaborationMaxInvestigations = 10, investigatorMaxCalls = 4;
    private int investigatorHybridTopK = 3;
    private String embeddingModelId = "dashscope/qwen3.7-text-embedding";
    private int chunkSize = 1200, chunkOverlap = 200, topK = 8;
    private int maxSteps = 10, maxToolCalls = 20;
    private int maxHistoryMessages = 12, maxContextCharacters = 48000;
    private int historySummaryThresholdTokens = 12000, historySummaryTargetTokens = 3000;
    private int historySummaryBatchTokens = 16000, historySummaryMaxCalls = 6, historySummaryOutputTokens = 8192;
    private int maxFullTextCharacters = 1000000, maxFullTextContextCharacters = 1100000;
    private int readCharacters = 12000;
    private int hybridCandidates = 20;
    private String plannerOutputMode = "JSON_OBJECT";
    private int plannerDiagnosticCharacters = 12000;
    private int contextOutputTokens = 8192;
    private boolean contextManagementEnabled = true;
    private int contextInputTokens = 0, fullContextInputTokens = 0, synthesisReserveTokens = 6000;
}
