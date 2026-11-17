package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.Transaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact subset dynamic programming with a fixed input bound, never a large-window solver. */
final class SmallSetExactBatchPacker {

    static final int MAX_TRANSACTIONS = 18;
    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();

    List<AuditBatch> pack(List<Transaction> transactions) {
        if (transactions.isEmpty() || transactions.size() > MAX_TRANSACTIONS) {
            throw new IllegalArgumentException("Exact packing accepts one to " + MAX_TRANSACTIONS + " transactions");
        }
        long[] amounts = transactions.stream().mapToLong(transaction ->
                transaction.amount().abs().movePointRight(2).longValueExact()).toArray();
        for (long amount : amounts) {
            if (amount > CAPACITY) {
                throw new IllegalArgumentException("A transaction exceeds the batch capacity");
            }
        }
        int states = 1 << transactions.size();
        int[] bins = new int[states];
        long[] lastLoad = new long[states];
        byte[] lastItem = new byte[states];
        Arrays.fill(bins, Integer.MAX_VALUE);
        bins[0] = 1;

        // For each subset, the fewest bins and then the lightest last bin dominate
        // other arrangements. Considering every final item explores all orders.
        for (int subset = 1; subset < states; subset++) {
            for (int remaining = subset; remaining != 0; remaining &= remaining - 1) {
                int item = Integer.numberOfTrailingZeros(remaining);
                int previous = subset ^ (1 << item);
                boolean fits = lastLoad[previous] + amounts[item] <= CAPACITY;
                int candidateBins = bins[previous] + (fits ? 0 : 1);
                long candidateLoad = fits ? lastLoad[previous] + amounts[item] : amounts[item];
                if (candidateBins < bins[subset]
                        || (candidateBins == bins[subset] && candidateLoad < lastLoad[subset])) {
                    bins[subset] = candidateBins;
                    lastLoad[subset] = candidateLoad;
                    lastItem[subset] = (byte) item;
                }
            }
        }

        int[] order = new int[transactions.size()];
        int subset = states - 1;
        for (int position = order.length - 1; position >= 0; position--) {
            int item = lastItem[subset];
            order[position] = item;
            subset ^= 1 << item;
        }
        List<AuditBatch> batches = new ArrayList<>();
        List<Transaction> current = new ArrayList<>();
        long load = 0;
        for (int item : order) {
            if (load + amounts[item] > CAPACITY) {
                batches.add(new AuditBatch(current));
                current = new ArrayList<>();
                load = 0;
            }
            current.add(transactions.get(item));
            load += amounts[item];
        }
        batches.add(new AuditBatch(current));
        return List.copyOf(batches);
    }
}
