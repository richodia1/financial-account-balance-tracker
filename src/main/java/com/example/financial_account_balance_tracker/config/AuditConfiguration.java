package com.example.financial_account_balance_tracker.config;

import com.example.financial_account_balance_tracker.audit.AsyncAuditDispatcher;
import com.example.financial_account_balance_tracker.audit.AuditSubmissionHandler;
import com.example.financial_account_balance_tracker.audit.JsonAuditSubmissionHandler;
import com.example.financial_account_balance_tracker.service.AuditBatchingService;
import com.example.financial_account_balance_tracker.service.TransactionAuditService;
import com.example.financial_account_balance_tracker.service.impl.OptimizingAuditBatchingService;
import com.example.financial_account_balance_tracker.service.impl.InMemoryTransactionAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditBatchingService.class)
    AuditBatchingService auditBatchingService() {
        return new OptimizingAuditBatchingService();
    }

    @Bean
    @ConditionalOnMissingBean(AuditSubmissionHandler.class)
    AuditSubmissionHandler auditSubmissionHandler(AuditBatchingService batchingService, JsonMapper jsonMapper) {
        return new JsonAuditSubmissionHandler(batchingService, jsonMapper, System.out);
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
