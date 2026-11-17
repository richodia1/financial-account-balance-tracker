package com.example.financial_account_balance_tracker.audit;

import com.example.financial_account_balance_tracker.model.AuditSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, non-blocking hand-off to a dedicated audit worker. Failed in-flight
 * work and queued submissions remain available in memory instead of being dropped.
 */
public final class AsyncAuditDispatcher implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncAuditDispatcher.class);

    private final int submissionSize;
    private final BlockingQueue<AuditSubmission> queue;
    private final AuditSubmissionHandler handler;
    private volatile boolean running;
    private volatile AuditSubmission inFlight;
    private volatile RuntimeException failure;
    private Thread worker;

    public AsyncAuditDispatcher(int submissionSize, int queueCapacity, AuditSubmissionHandler handler) {
        if (submissionSize < 1) {
            throw new IllegalArgumentException("Audit submission size must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("Audit queue capacity must be positive");
        }
        this.submissionSize = submissionSize;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.handler = Objects.requireNonNull(handler);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (failure != null || (worker != null && worker.isAlive())) {
            throw new IllegalStateException("The previous audit worker has not stopped cleanly", failure);
        }
        running = true;
        worker = Thread.ofPlatform().name("audit-dispatcher").unstarted(this::dispatch);
        try {
            worker.start();
        } catch (RuntimeException exception) {
            running = false;
            throw exception;
        }
    }

    public synchronized void submit(AuditSubmission submission) {
        Objects.requireNonNull(submission, "Audit submission must not be null");
        if (submission.transactions().size() != submissionSize) {
            throw new IllegalArgumentException("Audit submission must contain exactly " + submissionSize + " transactions");
        }
        if (!running || failure != null) {
            throw new RejectedExecutionException("Audit dispatcher is not accepting submissions", failure);
        }
        if (!queue.offer(submission)) {
            throw new RejectedExecutionException("Audit submission queue is full");
        }
    }

    private void dispatch() {
        try {
            while (running || !queue.isEmpty()) {
                AuditSubmission submission = queue.poll(100, TimeUnit.MILLISECONDS);
                if (submission == null) {
                    continue;
                }
                inFlight = submission;
                handler.handle(submission);
                inFlight = null;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(new IllegalStateException("Audit worker interrupted; pending work is retained", exception));
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    private void fail(RuntimeException exception) {
        synchronized (this) {
            failure = exception;
            running = false;
        }
        LOG.error("Audit processing stopped; in-flight and queued submissions are retained in memory", exception);
    }

    @Override
    public void stop() {
        Thread toJoin;
        synchronized (this) {
            running = false;
            toJoin = worker;
        }
        if (toJoin == null || toJoin == Thread.currentThread()) {
            return;
        }
        try {
            toJoin.join(5000);
            if (toJoin.isAlive()) {
                toJoin.interrupt();
                toJoin.join(5000);
                if (toJoin.isAlive()) {
                    LOG.warn("Audit worker did not terminate within the shutdown timeout");
                }
            }
        } catch (InterruptedException exception) {
            toJoin.interrupt();
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

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    public Optional<AuditSubmission> failedSubmission() {
        return failure == null ? Optional.empty() : Optional.ofNullable(inFlight);
    }

    int queuedSubmissionCount() {
        return queue.size();
    }
}
