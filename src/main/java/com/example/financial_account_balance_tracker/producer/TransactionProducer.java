package com.example.financial_account_balance_tracker.producer;

import com.example.financial_account_balance_tracker.enums.TransactionType;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Owns one dedicated scheduled thread per transaction type and their lifecycle.
 */
public final class TransactionProducer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionProducer.class);
    private static final int TRANSACTIONS_PER_SECOND = 25;
    private static final long PERIOD_MILLIS = 1000 / TRANSACTIONS_PER_SECOND;

    private final BankAccountService accountService;
    private final RandomTransactionFactory transactionFactory;
    private final Function<String, ScheduledExecutorService> executorFactory;

    private volatile boolean running;
    private ScheduledExecutorService creditExecutor;
    private ScheduledExecutorService debitExecutor;
    private ScheduledFuture<?> creditTask;
    private ScheduledFuture<?> debitTask;

    public TransactionProducer(BankAccountService accountService, RandomTransactionFactory transactionFactory) {
        this(accountService, transactionFactory, name -> Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(name).factory()));
    }

    TransactionProducer(BankAccountService accountService, RandomTransactionFactory transactionFactory,
            Function<String, ScheduledExecutorService> executorFactory) {
        this.accountService = Objects.requireNonNull(accountService);
        this.transactionFactory = Objects.requireNonNull(transactionFactory);
        this.executorFactory = Objects.requireNonNull(executorFactory);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if ((creditExecutor != null && !creditExecutor.isTerminated())
                || (debitExecutor != null && !debitExecutor.isTerminated())) {
            throw new IllegalStateException("Previous producer threads have not terminated");
        }

        creditExecutor = null;
        debitExecutor = null;
        try {
            creditExecutor = executorFactory.apply("credit-producer");
            debitExecutor = executorFactory.apply("debit-producer");
            running = true;
            creditTask = creditExecutor.scheduleAtFixedRate(
                    () -> produce(TransactionType.CREDIT), 0, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            debitTask = debitExecutor.scheduleAtFixedRate(
                    () -> produce(TransactionType.DEBIT), 0, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            LOG.info("Started transaction producers: {} credits/s and {} debits/s",
                    TRANSACTIONS_PER_SECOND, TRANSACTIONS_PER_SECOND);
        } catch (RuntimeException exception) {
            requestStop();
            throw exception;
        }
    }

    private void produce(TransactionType type) {
        if (!running) {
            return;
        }
        try {
            accountService.processTransaction(transactionFactory.create(type));
        } catch (RuntimeException exception) {
            LOG.error("Stopping both transaction producers after a {} processing failure", type, exception);
            // Request shutdown without waiting for the current worker to join itself.
            requestStop();
        }
    }

    @Override
    public void stop() {
        List<ScheduledExecutorService> executors = requestStop();
        try {
            for (ScheduledExecutorService executor : executors) {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        LOG.warn("A transaction producer worker did not terminate within the shutdown timeout");
                    }
                }
            }
        } catch (InterruptedException exception) {
            executors.forEach(ScheduledExecutorService::shutdownNow);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    private synchronized List<ScheduledExecutorService> requestStop() {
        running = false;
        if (creditTask != null) {
            creditTask.cancel(false);
            creditTask = null;
        }
        if (debitTask != null) {
            debitTask.cancel(false);
            debitTask = null;
        }
        List<ScheduledExecutorService> executors = new ArrayList<>(2);
        if (creditExecutor != null) {
            executors.add(creditExecutor);
        }
        if (debitExecutor != null) {
            executors.add(debitExecutor);
        }
        executors.forEach(ScheduledExecutorService::shutdown);
        return executors;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
