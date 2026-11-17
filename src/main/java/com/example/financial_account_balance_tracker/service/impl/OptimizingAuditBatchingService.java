package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.AuditBatchingService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Fast initial packing with exact small-set solving and bounded-search repair. */
public final class OptimizingAuditBatchingService implements AuditBatchingService {

    private static final int MAX_REPAIR_BATCHES = 6;
    private static final int MAX_REPAIR_PASSES = 2;
    private final AuditBatchingService initialPacker = new BestFitDecreasingAuditBatchingService();
    private final SmallSetExactBatchPacker exactPacker = new SmallSetExactBatchPacker();
    private final BoundedBatchRepacker boundedRepacker;

    public OptimizingAuditBatchingService() {
        this(new BoundedBatchRepacker());
    }

    OptimizingAuditBatchingService(BoundedBatchRepacker boundedRepacker) {
        this.boundedRepacker = Objects.requireNonNull(boundedRepacker);
    }

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
            Optional<List<AuditBatch>> attempted = transactions.size() <= SmallSetExactBatchPacker.MAX_TRANSACTIONS
                    ? Optional.of(exactPacker.pack(transactions))
                    : boundedRepacker.repack(transactions, selected.size() - 1);
            if (attempted.isEmpty()) {
                break;
            }
            List<AuditBatch> replacement = attempted.orElseThrow();
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
        long[] lightest = new long[MAX_REPAIR_BATCHES];
        int lightestCount = 0;
        List<AuditBatch> candidates = new ArrayList<>();
        for (AuditBatch batch : batches) {
            if (batch.countOfTransactions() <= BoundedBatchRepacker.MAX_TRANSACTIONS
                    && batch.totalValueOfAllTransactions().compareTo(AuditBatch.MAX_ABSOLUTE_VALUE) < 0) {
                candidates.add(batch);
                long amount = batch.totalValueOfAllTransactions().movePointRight(2).longValueExact();
                if (lightestCount < MAX_REPAIR_BATCHES) {
                    lightest[lightestCount++] = amount;
                    Arrays.sort(lightest, 0, lightestCount);
                } else if (amount < lightest[MAX_REPAIR_BATCHES - 1]) {
                    lightest[MAX_REPAIR_BATCHES - 1] = amount;
                    Arrays.sort(lightest);
                }
            }
        }
        // The k lightest eligible batches are an optimistic volume bound for
        // every k-batch group. Skip sorting if no group of two through six could
        // eliminate a bin, even before considering the total item budget.
        long total = 0;
        boolean potentiallyReducible = false;
        for (int count = 1; count <= lightestCount; count++) {
            total += lightest[count - 1];
            if (count >= 2 && total <= (count - 1) * capacity) {
                potentiallyReducible = true;
                break;
            }
        }
        if (!potentiallyReducible) {
            return List.of();
        }
        candidates.sort(Comparator.comparing(AuditBatch::totalValueOfAllTransactions));
        List<AuditBatch> selected = new ArrayList<>();
        int transactions = 0;
        for (AuditBatch candidate : candidates) {
            if (transactions + candidate.countOfTransactions() <= BoundedBatchRepacker.MAX_TRANSACTIONS) {
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
