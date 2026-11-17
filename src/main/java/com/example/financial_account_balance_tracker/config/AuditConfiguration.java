package com.example.financial_account_balance_tracker.config;

import com.example.financial_account_balance_tracker.audit.AsyncAuditDispatcher;
import com.example.financial_account_balance_tracker.audit.AuditSubmissionHandler;
import com.example.financial_account_balance_tracker.service.TransactionAuditService;
import com.example.financial_account_balance_tracker.service.impl.InMemoryTransactionAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(AuditConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(AuditSubmissionHandler.class)
    AuditSubmissionHandler auditSubmissionHandler() {
        return submission -> LOG.info("Collected audit submission {} containing {} transactions",
                submission.sequence(), submission.transactions().size());
    }

    @Bean(destroyMethod = "stop")
    AsyncAuditDispatcher auditDispatcher(@Value("${tracker.audit.submission-size:1000}") int submissionSize,
            @Value("${tracker.audit.queue-capacity:4}") int queueCapacity, AuditSubmissionHandler handler) {
        return new AsyncAuditDispatcher(submissionSize, queueCapacity, handler);
    }

    @Bean
    TransactionAuditService transactionAuditService(
            @Value("${tracker.audit.submission-size:1000}") int submissionSize, AsyncAuditDispatcher dispatcher) {
        return new InMemoryTransactionAuditService(submissionSize, dispatcher::submit);
    }
}
