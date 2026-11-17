package com.example.financial_account_balance_tracker.config;

import com.example.financial_account_balance_tracker.audit.AsyncAuditDispatcher;
import com.example.financial_account_balance_tracker.audit.AuditSubmissionHandler;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.TransactionAuditService;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import com.example.financial_account_balance_tracker.service.impl.InMemoryBankAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AuditConfigurationTests {

    @Test
    void defaultsToExactlyOneThousandTransactionsAndDrainsTheCompletedWindowOnClose() {
        List<AuditSubmission> received = new CopyOnWriteArrayList<>();
        runner(received).run(context -> {
            assertThat(context).hasSingleBean(TransactionAuditService.class);
            assertThat(context.getBean(AsyncAuditDispatcher.class).isRunning()).isTrue();
            TransactionAuditService collector = context.getBean(TransactionAuditService.class);
            for (int i = 1; i <= 1001; i++) {
                collector.record(transaction(i));
            }
        });

        assertThat(received).hasSize(1);
        assertThat(received.get(0).transactions()).hasSize(1000);
    }

    @Test
    void doesNotPublishAnIncompleteWindowOnShutdown() {
        List<AuditSubmission> received = new CopyOnWriteArrayList<>();
        runner(received).run(context -> {
            TransactionAuditService collector = context.getBean(TransactionAuditService.class);
            for (int i = 1; i <= 999; i++) {
                collector.record(transaction(i));
            }
        });

        assertThat(received).isEmpty();
    }

    @Test
    void appliesAConfiguredSubmissionSize() {
        List<AuditSubmission> received = new CopyOnWriteArrayList<>();
        runner(received).withPropertyValues("tracker.audit.submission-size=3").run(context -> {
            TransactionAuditService collector = context.getBean(TransactionAuditService.class);
            collector.record(transaction(1));
            collector.record(transaction(2));
            collector.record(transaction(3));
        });

        assertThat(received).hasSize(1);
        assertThat(received.get(0).transactions()).hasSize(3);
    }

    @Test
    void slowAuditHandlingDoesNotBlockAccountProcessingOrBalanceReads() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        new ApplicationContextRunner()
                .withBean(AuditSubmissionHandler.class, () -> submission -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test audit handler was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test audit handler interrupted", exception);
                    }
                })
                .withUserConfiguration(AuditConfiguration.class, InMemoryBankAccountService.class)
                .run(context -> {
                    ExecutorService caller = Executors.newSingleThreadExecutor();
                    try {
                        BankAccountService account = context.getBean(BankAccountService.class);
                        Future<?> firstWindow = caller.submit(() -> {
                            for (int i = 1; i <= 1000; i++) {
                                account.processTransaction(transaction(i));
                            }
                        });
                        firstWindow.get(3, TimeUnit.SECONDS);
                        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

                        Future<BigDecimal> balance = caller.submit(() -> {
                            account.processTransaction(transaction(1001));
                            return account.retrieveExactBalance();
                        });

                        assertThat(balance.get(3, TimeUnit.SECONDS)).isEqualTo(new BigDecimal("200200.00"));
                    } finally {
                        release.countDown();
                        caller.shutdownNow();
                        assertThat(caller.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracker.audit.submission-size=0", "tracker.audit.queue-capacity=0"})
    void invalidConfigurationFailsStartup(String property) {
        runner(new CopyOnWriteArrayList<>()).withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner runner(List<AuditSubmission> received) {
        return new ApplicationContextRunner()
                .withBean(AuditSubmissionHandler.class, () -> received::add)
                .withUserConfiguration(AuditConfiguration.class);
    }

    private static Transaction transaction(long id) {
        return new Transaction(new UUID(0, id), new BigDecimal("200.00"));
    }
}
