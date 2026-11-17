package com.example.financial_account_balance_tracker.config;

import com.example.financial_account_balance_tracker.producer.RandomTransactionFactory;
import com.example.financial_account_balance_tracker.producer.TransactionProducer;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tracker.producer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProducerConfiguration {

    @Bean
    RandomTransactionFactory randomTransactionFactory() {
        return new RandomTransactionFactory();
    }

    @Bean(destroyMethod = "stop")
    TransactionProducer transactionProducer(BankAccountService accountService,
            RandomTransactionFactory transactionFactory) {
        return new TransactionProducer(accountService, transactionFactory);
    }
}
