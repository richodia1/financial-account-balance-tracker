package com.example.financial_account_balance_tracker.producer;

import com.example.financial_account_balance_tracker.enums.TransactionType;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionProducerTests {

    private BankAccountService service;
    private ScheduledExecutorService credits;
    private ScheduledExecutorService debits;
    private ScheduledFuture<?> creditTask;
    private ScheduledFuture<?> debitTask;
    private TransactionProducer producer;

    @BeforeEach
    void setUp() throws InterruptedException {
        service = mock(BankAccountService.class);
        credits = mock(ScheduledExecutorService.class);
        debits = mock(ScheduledExecutorService.class);
        creditTask = mock(ScheduledFuture.class);
        debitTask = mock(ScheduledFuture.class);
        doReturn(creditTask).when(credits).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        doReturn(debitTask).when(debits).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        when(credits.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(true);
        when(debits.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(true);
        when(credits.isTerminated()).thenReturn(true);
        when(debits.isTerminated()).thenReturn(true);
        RandomTransactionFactory factory = new RandomTransactionFactory((origin, bound) -> origin, UUID::randomUUID);
        producer = new TransactionProducer(service, factory, name -> switch (name) {
            case "credit-producer" -> credits;
            case "debit-producer" -> debits;
            default -> throw new IllegalArgumentException("Unexpected producer thread name");
        });
    }

    @AfterEach
    void stopProducer() {
        producer.stop();
    }

    @Test
    void schedulesEachTypeAtTwentyFiveTransactionsPerSecondOnSeparateExecutors() {
        producer.start();

        assertTrue(producer.isRunning());
        verify(credits).scheduleAtFixedRate(any(), eq(0L), eq(40L), eq(TimeUnit.MILLISECONDS));
        verify(debits).scheduleAtFixedRate(any(), eq(0L), eq(40L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void sendsCreditsAndDebitsToTheAccountService() {
        producer.start();

        tick(credits).run();
        tick(debits).run();

        ArgumentCaptor<Transaction> transactions = ArgumentCaptor.forClass(Transaction.class);
        verify(service, times(2)).processTransaction(transactions.capture());
        List<Transaction> values = transactions.getAllValues();
        assertEquals(new BigDecimal("200.00"), values.get(0).amount());
        assertEquals(new BigDecimal("-200.00"), values.get(1).amount());
    }

    @Test
    void repeatedStartDoesNotScheduleDuplicateProducers() {
        producer.start();
        producer.start();

        verify(credits, times(1)).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        verify(debits, times(1)).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    void stopsBothTasksAndPreventsFurtherDispatch() {
        producer.start();
        Runnable tick = tick(credits);

        producer.stop();
        tick.run();

        assertFalse(producer.isRunning());
        verify(creditTask).cancel(false);
        verify(debitTask).cancel(false);
        verify(credits).shutdown();
        verify(debits).shutdown();
        verifyNoInteractions(service);
    }

    @Test
    void processingFailureHaltsBothProducers() {
        doThrow(new IllegalStateException("Account processing failed")).when(service).processTransaction(any());
        producer.start();

        tick(credits).run();
        tick(debits).run();

        assertFalse(producer.isRunning());
        verify(credits).shutdown();
        verify(debits).shutdown();
        verify(service, times(1)).processTransaction(any());
    }

    @Test
    void failedSchedulingCleansUpBothExecutors() {
        doThrow(new RejectedExecutionException("Scheduling failed")).when(debits)
                .scheduleAtFixedRate(any(), anyLong(), anyLong(), any());

        assertThrows(RejectedExecutionException.class, producer::start);

        assertFalse(producer.isRunning());
        verify(creditTask).cancel(false);
        verify(credits).shutdown();
        verify(debits).shutdown();
    }

    @Test
    void forcesShutdownWhenGracefulTerminationTimesOut() throws InterruptedException {
        when(credits.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(false, true);
        producer.start();

        producer.stop();

        verify(credits).shutdownNow();
        verify(debits, never()).shutdownNow();
    }

    @Test
    void realWorkersUseOneDedicatedThreadForEachTransactionType() throws Exception {
        BankAccountService recordingService = mock(BankAccountService.class);
        Map<TransactionType, Set<String>> threads = new ConcurrentHashMap<>();
        Map<TransactionType, CountDownLatch> received = Map.of(
                TransactionType.CREDIT, new CountDownLatch(3),
                TransactionType.DEBIT, new CountDownLatch(3));
        doAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            threads.computeIfAbsent(transaction.type(), key -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().getName());
            received.get(transaction.type()).countDown();
            return null;
        }).when(recordingService).processTransaction(any());
        TransactionProducer actual = new TransactionProducer(recordingService, new RandomTransactionFactory());

        try {
            actual.start();
            assertTrue(received.get(TransactionType.CREDIT).await(3, TimeUnit.SECONDS));
            assertTrue(received.get(TransactionType.DEBIT).await(3, TimeUnit.SECONDS));
            actual.stop();

            assertEquals(Set.of("credit-producer"), threads.get(TransactionType.CREDIT));
            assertEquals(Set.of("debit-producer"), threads.get(TransactionType.DEBIT));
            assertFalse(actual.isRunning());
        } finally {
            actual.stop();
        }
    }

    private Runnable tick(ScheduledExecutorService executor) {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleAtFixedRate(task.capture(), eq(0L), eq(40L), eq(TimeUnit.MILLISECONDS));
        return task.getValue();
    }
}
