package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.AuditBatchingService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Fast initial packing followed by bounded exact repair; large inputs remain heuristic. */
public final class OptimizingAuditBatchingService implements AuditBatchingService {

    private static final int MAX_REPAIR_BATCHES = 6;
    private static final int MAX_REPAIR_PASSES = 2;
    private final AuditBatchingService initialPacker = new BestFitDecreasingAuditBatchingService();
    private final SmallSetExactBatchPacker exactPacker = new SmallSetExactBatchPacker();

    @Override
    public AuditBatchingResult pack(AuditSubmission submission) {
        AuditBatchingResult initial = initialPacker.pack(submission);
        if (initial.optimalityProven()) {
            return initial;
        }
        if (submission.transactions().size() <= SmallSetExactBatchPacker.MAX_TRANSACTIONS) {
            List<AuditBatch> exact = exactPacker.pack(submission.transactions());
            // Solving the entire submission proves its exact minimum, even if
            // the initial volume-based lower bound was weak.
            return new AuditBatchingResult(exact, exact.size());
        }

        List<AuditBatch> batches = initial.batches();
        for (int pass = 0; pass < MAX_REPAIR_PASSES && batches.size() > initial.batchCountLowerBound(); pass++) {
            List<AuditBatch> selected = selectRepairBatches(batches);
            if (selected.size() < 2 || !couldReduceCount(selected)) {
                break;
            }
            List<Transaction> transactions = selected.stream().flatMap(batch -> batch.transactions().stream()).toList();
            List<AuditBatch> replacement = exactPacker.pack(transactions);
            if (replacement.size() >= selected.size()) {
                break;
            }
            var removed = new HashSet<>(selected);
            List<AuditBatch> improved = new ArrayList<>();
            for (AuditBatch batch : batches) {
                if (!removed.contains(batch)) {
                    improved.add(batch);
                }
            }
            improved.addAll(replacement);
            batches = improved;
        }
        // An exact local solution is not a global lower bound. Preserve the
        // original bound unless the entire submission was solved above.
        return new AuditBatchingResult(batches, initial.batchCountLowerBound());
    }

    private static List<AuditBatch> selectRepairBatches(List<AuditBatch> batches) {
        long capacity = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
        long lightest = capacity;
        List<AuditBatch> candidates = new ArrayList<>();
        for (AuditBatch batch : batches) {
            if (batch.countOfTransactions() <= SmallSetExactBatchPacker.MAX_TRANSACTIONS
                    && batch.totalValueOfAllTransactions().compareTo(AuditBatch.MAX_ABSOLUTE_VALUE) < 0) {
                candidates.add(batch);
                lightest = Math.min(lightest, batch.totalValueOfAllTransactions().movePointRight(2).longValueExact());
            }
        }
        // If even six copies of the lightest eligible batch exceed five bins,
        // no group of at most six eligible batches can eliminate a bin.
        if (lightest * MAX_REPAIR_BATCHES > (MAX_REPAIR_BATCHES - 1) * capacity) {
            return List.of();
        }
        candidates.sort(Comparator.comparing(AuditBatch::totalValueOfAllTransactions));
        List<AuditBatch> selected = new ArrayList<>();
        int transactions = 0;
        for (AuditBatch candidate : candidates) {
            if (transactions + candidate.countOfTransactions() <= SmallSetExactBatchPacker.MAX_TRANSACTIONS) {
                selected.add(candidate);
                transactions += candidate.countOfTransactions();
                if (selected.size() == MAX_REPAIR_BATCHES) {
                    break;
                }
            }
        }
        return selected;
    }

    private static boolean couldReduceCount(List<AuditBatch> batches) {
        long total = batches.stream().mapToLong(batch ->
                batch.totalValueOfAllTransactions().movePointRight(2).longValueExact()).sum();
        long capacity = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
        return total <= (batches.size() - 1) * capacity;
    }
}
