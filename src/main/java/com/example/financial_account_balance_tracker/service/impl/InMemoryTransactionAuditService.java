package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.AuditUnavailableException;
import com.example.financial_account_balance_tracker.exception.InvalidTransactionException;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.TransactionAuditService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Builds disjoint, ordered windows and hands complete immutable snapshots to a
 * non-blocking publisher. A rejected hand-off leaves the partial window intact.
 */
public final class InMemoryTransactionAuditService implements TransactionAuditService {

    private final int submissionSize;
    private final Consumer<AuditSubmission> publisher;
    private final List<Transaction> pending;
    private long nextSequence = 1;

    public InMemoryTransactionAuditService(int submissionSize, Consumer<AuditSubmission> publisher) {
        if (submissionSize < 1) {
            throw new IllegalArgumentException("Audit submission size must be positive");
        }
        this.submissionSize = submissionSize;
        this.publisher = Objects.requireNonNull(publisher);
        this.pending = new ArrayList<>(submissionSize);
    }

    @Override
    public synchronized void record(Transaction transaction) {
        if (transaction == null) {
            throw new InvalidTransactionException("Transaction must not be null");
        }
        if (pending.size() + 1 < submissionSize) {
            pending.add(transaction);
            return;
        }

        List<Transaction> complete = new ArrayList<>(submissionSize);
        complete.addAll(pending);
        complete.add(transaction);
        AuditSubmission submission = new AuditSubmission(nextSequence, complete);
        long followingSequence = Math.incrementExact(nextSequence);
        try {
            publisher.accept(submission);
        } catch (AuditUnavailableException exception) {
            throw exception;
        } catch (RejectedExecutionException exception) {
            throw new AuditUnavailableException("Audit submission could not be accepted", exception);
        }
        // Commit the window only after the non-blocking hand-off succeeds.
        pending.clear();
        nextSequence = followingSequence;
    }

    public synchronized int pendingTransactionCount() {
        return pending.size();
    }
}
