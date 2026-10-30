package com.yupi.yuaiagent.reading.persistence;

import com.yupi.yuaiagent.reading.persistence.mapper.ReadingRecoveryMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs once during bean initialization, before reading services become available.
 */
public class ReadingStartupRecovery {
    private final ReadingRecoveryMapper mapper;
    private final TransactionTemplate transactions;

    public ReadingStartupRecovery(ReadingRecoveryMapper mapper, TransactionTemplate transactions) {
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public void recover() {
        transactions.executeWithoutResult(tx -> {
            mapper.interruptKnowledge();
            mapper.interruptRuns();
        });
    }
}
