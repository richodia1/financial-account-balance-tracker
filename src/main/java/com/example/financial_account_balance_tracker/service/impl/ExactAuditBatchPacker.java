package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.AuditProcessingException;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Certifies a minimum by bounds or complete, iterative bin-assignment search. */
final class ExactAuditBatchPacker {

    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();

    AuditBatchingResult minimize(AuditSubmission submission, AuditBatchingResult incumbent) {
        Item[] items = items(submission.transactions());
        int lowerBound = Math.max(incumbent.batchCountLowerBound(), lowerBound(items));
        if (incumbent.batches().size() == lowerBound) {
            return new AuditBatchingResult(incumbent.batches(), lowerBound);
        }
        List<AuditBatch> lookahead = new LookaheadAuditBatchPacker().pack(submission);
        List<AuditBatch> improved = lookahead.size() < incumbent.batches().size() ? lookahead : incumbent.batches();
        if (improved.size() == lowerBound) {
            return new AuditBatchingResult(improved, lowerBound);
        }
        // Each smaller count is either proved infeasible or yields a complete
        // assignment. There is no node/time budget that can accept an incumbent
        // as optimal merely because the search has stopped making progress.
        for (int target = lowerBound; target < improved.size(); target++) {
            Optional<List<AuditBatch>> feasible = new Search(items, target).solve();
            if (feasible.isPresent()) {
                return new AuditBatchingResult(feasible.orElseThrow(), target);
            }
        }
        return new AuditBatchingResult(improved, improved.size());
    }

    /** Used by independent feasibility tests as well as minimum-count search. */
    Optional<List<AuditBatch>> packInto(List<Transaction> transactions, int targetBatches) {
        if (transactions.isEmpty() || targetBatches < 1) {
            throw new IllegalArgumentException("Exact packing needs transactions and a positive batch count");
        }
        Item[] items = items(transactions);
        return targetBatches < lowerBound(items) ? Optional.empty() : new Search(items, targetBatches).solve();
    }

    private static Item[] items(List<Transaction> transactions) {
        Item[] items = new Item[transactions.size()];
        for (int i = 0; i < items.length; i++) {
            Transaction transaction = transactions.get(i);
            long pence = transaction.amount().abs().movePointRight(2).longValueExact();
            if (pence > CAPACITY) {
                throw new IllegalArgumentException("A transaction exceeds the batch capacity");
            }
            items[i] = new Item(transaction, pence);
        }
        Arrays.sort(items, Comparator.comparingLong(Item::pence).reversed());
        return items;
    }

    private static int lowerBound(Item[] sorted) {
        long total = 0;
        int bound = 1;
        for (int i = 0; i < sorted.length; i++) {
            long weight = sorted[i].pence();
            total += weight;
            // At most floor(capacity / weight) members of this sorted prefix
            // can share a bin. This bound also covers items larger than half.
            long perBin = CAPACITY / weight;
            bound = Math.max(bound, Math.toIntExact((i + perBin) / perBin));
        }
        return Math.max(bound, Math.toIntExact((total + CAPACITY - 1) / CAPACITY));
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AuditProcessingException("Exact audit optimisation was interrupted");
        }
    }

    private record Item(Transaction transaction, long pence) {
    }

    private static final class Search {
        private static final long MEMO_BYTES = 16L * 1024 * 1024;
        private final Item[] items;
        private final long[] loads;
        private final int[] assignment;
        private final boolean[] opened;
        private final long[] lastTriedLoad;
        private final long smallestItem;
        private final long slackBudget;
        private long wastedCapacity;
        private int used;
        private final HashSet<State> infeasible = new HashSet<>();
        private long memoBytes;

        private Search(Item[] items, int target) {
            this.items = items;
            loads = new long[Math.min(target, items.length)];
            assignment = new int[items.length];
            opened = new boolean[items.length];
            lastTriedLoad = new long[items.length];
            Arrays.fill(lastTriedLoad, CAPACITY + 1);
            smallestItem = items[items.length - 1].pence();
            slackBudget = loads.length * CAPACITY - Arrays.stream(items).mapToLong(Item::pence).sum();
        }

        private Optional<List<AuditBatch>> solve() {
            int depth = 0;
            while (depth >= 0) {
                checkInterrupted();
                if (depth == items.length) {
                    return Optional.of(reconstruct());
                }
                long weight = items[depth].pence();
                boolean previouslyExcluded = lastTriedLoad[depth] == CAPACITY + 1 && !infeasible.isEmpty()
                        && infeasible.contains(state(depth));
                int bin = previouslyExcluded ? -1 : nextBin(depth, weight);
                if (bin >= 0) {
                    lastTriedLoad[depth] = loads[bin];
                    assignment[depth] = bin;
                    opened[depth] = bin == used;
                    if (opened[depth]) {
                        used++;
                    }
                    changeLoad(bin, weight);
                    // Residual space below the smallest item can never be used.
                    // Rejecting such a state is a proof-based pruning rule.
                    if (wastedCapacity > slackBudget) {
                        undo(depth);
                    } else {
                        depth++;
                        if (depth < items.length) {
                            lastTriedLoad[depth] = CAPACITY + 1;
                        }
                    }
                } else {
                    if (!previouslyExcluded) {
                        rememberInfeasible(depth);
                    }
                    lastTriedLoad[depth] = CAPACITY + 1;
                    depth--;
                    if (depth >= 0) {
                        undo(depth);
                    }
                }
            }
            return Optional.empty();
        }

        private State state(int depth) {
            long[] sorted = Arrays.copyOf(loads, used);
            Arrays.sort(sorted);
            long[] encoded = new long[used * 2];
            int end = 0;
            for (long load : sorted) {
                if (end > 0 && encoded[end - 2] == load) {
                    encoded[end - 1]++;
                } else {
                    encoded[end++] = load;
                    encoded[end++] = 1;
                }
            }
            return new State(depth, Arrays.copyOf(encoded, end));
        }

        private void rememberInfeasible(int depth) {
            State state = state(depth);
            long bytes = 128L + state.loads.length * Long.BYTES;
            if (bytes > MEMO_BYTES) {
                return;
            }
            if (memoBytes + bytes > MEMO_BYTES) {
                // Eviction forgets an acceleration, not an unexplored branch.
                infeasible.clear();
                memoBytes = 0;
            }
            if (infeasible.add(state)) {
                memoBytes += bytes;
            }
        }

        private int nextBin(int depth, long weight) {
            int chosen = -1;
            long fullest = -1;
            for (int bin = 0; bin < used; bin++) {
                long load = loads[bin];
                if (load < lastTriedLoad[depth] && load > fullest && load + weight <= CAPACITY) {
                    chosen = bin;
                    fullest = load;
                }
            }
            // Equal-load bins are interchangeable for all remaining items.
            // Visiting one representative per load removes only permutations.
            if (chosen < 0 && used < loads.length && lastTriedLoad[depth] > 0) {
                chosen = used;
            }
            return chosen;
        }

        private void changeLoad(int bin, long delta) {
            long oldFree = CAPACITY - loads[bin];
            wastedCapacity -= oldFree < smallestItem ? oldFree : 0;
            loads[bin] += delta;
            long newFree = CAPACITY - loads[bin];
            wastedCapacity += newFree < smallestItem ? newFree : 0;
        }

        private void undo(int depth) {
            changeLoad(assignment[depth], -items[depth].pence());
            if (opened[depth]) {
                used--;
            }
        }

        private List<AuditBatch> reconstruct() {
            List<List<Transaction>> bins = new ArrayList<>(used);
            for (int bin = 0; bin < used; bin++) {
                bins.add(new ArrayList<>());
            }
            for (int i = 0; i < items.length; i++) {
                bins.get(assignment[i]).add(items[i].transaction());
            }
            return bins.stream().map(AuditBatch::new).toList();
        }

        private static final class State {
            private final int depth;
            private final long[] loads;
            private final int hash;

            private State(int depth, long[] loads) {
                this.depth = depth;
                this.loads = loads;
                hash = 31 * depth + Arrays.hashCode(loads);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof State state && depth == state.depth && Arrays.equals(loads, state.loads);
            }
        }
    }
}
