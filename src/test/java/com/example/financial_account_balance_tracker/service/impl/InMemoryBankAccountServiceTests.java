package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBankAccountServiceTests {

    private final List<AuditSubmission> submissions = new ArrayList<>();
    private final InMemoryTransactionAuditService auditService =
            new InMemoryTransactionAuditService(1000, submissions::add);
    private final BankAccountService service = new InMemoryBankAccountService(auditService);

    @Test
    void startsWithZeroBalance() {
        assertAll(
                () -> assertEquals(new BigDecimal("0.00"), service.retrieveExactBalance()),
                () -> assertEquals(0.0, service.retrieveBalance()));
    }

    @ParameterizedTest
    @MethodSource("balanceScenarios")
    void calculatesCreditsDebitsAndPenceExactly(List<String> amounts, String expected) {
        for (int i = 0; i < amounts.size(); i++) {
            service.processTransaction(transaction(i + 1, amounts.get(i)));
        }

        assertEquals(new BigDecimal(expected), service.retrieveExactBalance());
    }

    private static Stream<Arguments> balanceScenarios() {
        return Stream.of(
                Arguments.of(List.of("200.01"), "200.01"),
                Arguments.of(List.of("-200.01"), "-200.01"),
                Arguments.of(List.of("500000", "-200.01", "200.02", "-500000"), "0.01"),
                Arguments.of(List.of("0.10", "0.20"), "0.30"),
                Arguments.of(List.of("0.10", "0.20", "-0.30"), "0.00"),
                Arguments.of(List.of("500000", "500000"), "1000000.00"));
    }

    @Test
    void preservesTheRequiredDoubleBalanceInterface() {
        service.processTransaction(transaction(1, "200.01"));

        assertEquals(200.01, service.retrieveBalance());
    }

    @Test
    void previouslyRetrievedBalanceRemainsAnUnchangedSnapshot() {
        service.processTransaction(transaction(1, "200.00"));
        BigDecimal snapshot = service.retrieveExactBalance();

        service.processTransaction(transaction(2, "-50.00"));

        assertAll(
                () -> assertEquals(new BigDecimal("200.00"), snapshot),
                () -> assertEquals(new BigDecimal("150.00"), service.retrieveExactBalance()));
    }

    @Test
    void rejectsNullTransactionWithoutChangingBalance() {
        service.processTransaction(transaction(1, "200.01"));

        assertThrows(NullPointerException.class, () -> service.processTransaction(null));
        assertEquals(new BigDecimal("200.01"), service.retrieveExactBalance());
    }

    @Test
    void rejectedAuditHandoffLeavesBalanceAndPartialWindowUnchanged() {
        AtomicBoolean accept = new AtomicBoolean(false);
        List<AuditSubmission> accepted = new ArrayList<>();
        InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(2, submission -> {
            if (!accept.get()) {
                throw new RejectedExecutionException("Audit queue full");
            }
            accepted.add(submission);
        });
        BankAccountService account = new InMemoryBankAccountService(collector);
        account.processTransaction(transaction(1, "200.00"));

        assertThrows(RejectedExecutionException.class,
                () -> account.processTransaction(transaction(2, "-50.00")));
        assertEquals(new BigDecimal("200.00"), account.retrieveExactBalance());
        assertEquals(1, collector.pendingTransactionCount());
        assertTrue(accepted.isEmpty());

        accept.set(true);
        account.processTransaction(transaction(2, "-50.00"));
        assertEquals(new BigDecimal("150.00"), account.retrieveExactBalance());
        assertEquals(1, accepted.size());
        assertEquals(1, accepted.get(0).sequence());
        assertEquals(0, collector.pendingTransactionCount());
    }

    @Test
    void handlesConcurrentCreditsDebitsAndBalanceEnquiriesWithoutLosingUpdates() throws Exception {
        int workerCount = 8;
        int transactionsPerWorker = 1000;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);

        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                int workerId = worker;
                tasks.add(executor.submit(() -> {
                    BigDecimal amount = new BigDecimal(workerId % 2 == 0 ? "200.03" : "-200.01");
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }

                    for (int i = 0; i < transactionsPerWorker; i++) {
                        UUID id = new UUID(workerId, i + 1);
                        service.processTransaction(new Transaction(id, amount));
                        if (i % 32 == 0) {
                            BigDecimal snapshot = service.retrieveExactBalance();
                            assertEquals(2, snapshot.scale());
                            assertTrue(snapshot.compareTo(new BigDecimal("-800040.00")) >= 0);
                            assertTrue(snapshot.compareTo(new BigDecimal("800120.00")) <= 0);
                            assertTrue(Double.isFinite(service.retrieveBalance()));
                        }
                    }
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "All workers must be ready");
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }

            // Four workers add 200.03 and four subtract 200.01, each 1000 times.
            assertAll(
                    () -> assertEquals(new BigDecimal("80.00"), service.retrieveExactBalance()),
                    () -> assertEquals(80.0, service.retrieveBalance()));
            assertEquals(8, submissions.size());
            Set<UUID> ids = new HashSet<>();
            for (int i = 0; i < submissions.size(); i++) {
                AuditSubmission submission = submissions.get(i);
                assertEquals(i + 1, submission.sequence());
                assertEquals(1000, submission.transactions().size());
                submission.transactions().forEach(transaction -> assertTrue(ids.add(transaction.id())));
            }
            assertEquals(8000, ids.size());
            assertEquals(0, auditService.pendingTransactionCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Workers must stop");
        }
    }

    private static Transaction transaction(long id, String amount) {
        return new Transaction(new UUID(0, id), new BigDecimal(amount));
    }
}
