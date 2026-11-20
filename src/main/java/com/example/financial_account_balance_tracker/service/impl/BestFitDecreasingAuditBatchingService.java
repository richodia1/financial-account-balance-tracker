package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.AuditBatchingService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * O(N log N) time and O(N) space. This heuristic aims to minimize batch count;
 * it proves optimality only when its count equals the reported lower bound.
 */
public final class BestFitDecreasingAuditBatchingService implements AuditBatchingService {

    private static final long CAPACITY_PENCE = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();

    @Override
    public AuditBatchingResult pack(AuditSubmission submission) {
        if (submission == null) {
            throw new InvalidAuditSubmissionException("Audit submission must not be null");
        }
        List<WeightedTransaction> sorted = new ArrayList<>(submission.transactions().size());
        long totalPence = 0;
        int moreThanHalfFull = 0;
        for (Transaction transaction : submission.transactions()) {
            if (transaction.amount().abs().compareTo(AuditBatch.MAX_ABSOLUTE_VALUE) > 0) {
                throw new InvalidAuditSubmissionException("A transaction cannot exceed the GBP 1,000,000 batch capacity");
            }
            long pence = transaction.amount().abs().movePointRight(2).longValueExact();
            sorted.add(new WeightedTransaction(transaction, pence));
            totalPence += pence;
            if (pence > CAPACITY_PENCE / 2) {
                moreThanHalfFull++;
            }
        }
        // Stable sorting preserves the input order for equal magnitudes.
        sorted.sort(Comparator.comparingLong(WeightedTransaction::absolutePence).reversed());

        TreeMap<Long, ArrayDeque<OpenBatch>> byRemainingCapacity = new TreeMap<>();
        List<OpenBatch> batches = new ArrayList<>();
        for (WeightedTransaction transaction : sorted) {
            var entry = byRemainingCapacity.ceilingEntry(transaction.absolutePence());
            OpenBatch batch;
            if (entry == null) {
                batch = new OpenBatch();
                batches.add(batch);
            } else {
                batch = entry.getValue().removeFirst();
                if (entry.getValue().isEmpty()) {
                    byRemainingCapacity.remove(entry.getKey());
                }
            }
            batch.transactions.add(transaction.transaction());
            batch.remainingPence -= transaction.absolutePence();
            if (batch.remainingPence > 0) {
                byRemainingCapacity.computeIfAbsent(batch.remainingPence, key -> new ArrayDeque<>()).addLast(batch);
            }
        }

        int volumeLowerBound = Math.toIntExact((totalPence + CAPACITY_PENCE - 1) / CAPACITY_PENCE);
        return new AuditBatchingResult(batches.stream().map(batch -> new AuditBatch(batch.transactions)).toList(),
                Math.max(volumeLowerBound, moreThanHalfFull));
    }

    private record WeightedTransaction(Transaction transaction, long absolutePence) {
    }

    private static final class OpenBatch {
        private final List<Transaction> transactions = new ArrayList<>();
        private long remainingPence = CAPACITY_PENCE;
    }
}
