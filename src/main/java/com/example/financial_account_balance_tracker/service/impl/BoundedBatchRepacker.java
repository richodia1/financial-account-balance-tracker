package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.Transaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Searches for a feasible reduction with fixed item, bin and search-node bounds. */
final class BoundedBatchRepacker {

    static final int MAX_TRANSACTIONS = 128;
    private static final int MAX_TARGET_BATCHES = 5;
    private static final int DEFAULT_NODE_BUDGET = 100000;
    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
    private final int nodeBudget;

    BoundedBatchRepacker() {
        this(DEFAULT_NODE_BUDGET);
    }

    BoundedBatchRepacker(int nodeBudget) {
        if (nodeBudget < 0) {
            throw new IllegalArgumentException("Search node budget must be nonnegative");
        }
        this.nodeBudget = nodeBudget;
    }

    Optional<List<AuditBatch>> repack(List<Transaction> transactions, int targetBatches) {
        if (transactions.isEmpty() || transactions.size() > MAX_TRANSACTIONS) {
            throw new IllegalArgumentException("Bounded repacking accepts one to " + MAX_TRANSACTIONS + " transactions");
        }
        if (targetBatches < 1 || targetBatches > MAX_TARGET_BATCHES) {
            throw new IllegalArgumentException("Target batch count must be between one and " + MAX_TARGET_BATCHES);
        }
        List<Item> items = new ArrayList<>(transactions.size());
        long total = 0;
        for (Transaction transaction : transactions) {
            if (transaction.amount().abs().compareTo(AuditBatch.MAX_ABSOLUTE_VALUE) > 0) {
                throw new InvalidAuditSubmissionException("A transaction exceeds the batch capacity");
            }
            long amount = transaction.amount().abs().movePointRight(2).longValueExact();
            items.add(new Item(transaction, amount));
            total += amount;
        }
        if (total > targetBatches * CAPACITY) {
            return Optional.empty();
        }
        items.sort(Comparator.comparingLong(Item::pence).reversed());
        Search search = new Search(items, targetBatches, nodeBudget);
        if (!search.assign(0)) {
            // Exhaustion and infeasibility both mean no replacement. Neither is
            // treated as proof about the minimum for the whole submission.
            return Optional.empty();
        }
        List<List<Transaction>> assigned = new ArrayList<>();
        for (int bin = 0; bin < targetBatches; bin++) {
            assigned.add(new ArrayList<>());
        }
        for (int item = 0; item < items.size(); item++) {
            assigned.get(search.assignment[item]).add(items.get(item).transaction());
        }
        return Optional.of(assigned.stream().filter(batch -> !batch.isEmpty()).map(AuditBatch::new).toList());
    }

    private record Item(Transaction transaction, long pence) {
    }

    private static final class Search {
        private final List<Item> items;
        private final long[] loads;
        private final int[] assignment;
        private final int nodeBudget;
        private int visited;

        private Search(List<Item> items, int targetBatches, int nodeBudget) {
            this.items = items;
            this.loads = new long[targetBatches];
            this.assignment = new int[items.size()];
            Arrays.fill(assignment, -1);
            this.nodeBudget = nodeBudget;
        }

        private boolean assign(int item) {
            if (visited == nodeBudget) {
                return false;
            }
            visited++;
            if (item == items.size()) {
                return true;
            }
            long amount = items.get(item).pence();
            for (int bin = 0; bin < loads.length; bin++) {
                if (loads[bin] + amount > CAPACITY || equivalentEarlierBin(bin)) {
                    continue;
                }
                loads[bin] += amount;
                assignment[item] = bin;
                if (assign(item + 1)) {
                    return true;
                }
                loads[bin] -= amount;
                assignment[item] = -1;
                if (visited == nodeBudget) {
                    return false;
                }
            }
            return false;
        }

        private boolean equivalentEarlierBin(int bin) {
            for (int earlier = 0; earlier < bin; earlier++) {
                if (loads[earlier] == loads[bin]) {
                    return true;
                }
            }
            return false;
        }
    }
}
