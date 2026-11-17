package com.example.financial_account_balance_tracker.config;

import com.example.financial_account_balance_tracker.producer.RandomTransactionFactory;
import com.example.financial_account_balance_tracker.producer.TransactionProducer;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import com.example.financial_account_balance_tracker.service.impl.InMemoryBankAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProducerConfiguration.class)
            .withBean(BankAccountService.class, InMemoryBankAccountService::new);

    @Test
    void canDisableAutomaticGeneration() {
        runner.withPropertyValues("tracker.producer.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TransactionProducer.class);
            assertThat(context).doesNotHaveBean(RandomTransactionFactory.class);
            assertThat(context.getBean(BankAccountService.class).retrieveExactBalance())
                    .isEqualTo(new BigDecimal("0.00"));
        });
    }

    @Test
    void automaticallyStartsAndStopsEnabledProducers() {
        AtomicReference<TransactionProducer> producer = new AtomicReference<>();
        runner.withPropertyValues("tracker.producer.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(TransactionProducer.class);
            assertThat(context).hasSingleBean(RandomTransactionFactory.class);
            producer.set(context.getBean(TransactionProducer.class));
            assertThat(producer.get().isRunning()).isTrue();
        });

        assertThat(producer.get().isRunning()).isFalse();
    }

    @Test
    void generationIsEnabledByDefault() {
        runner.run(context -> assertThat(context.getBean(TransactionProducer.class).isRunning()).isTrue());
    }
}
