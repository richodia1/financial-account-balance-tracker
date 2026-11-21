package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

/** Constructs a second feasible incumbent with deterministic two-item lookahead. */
final class LookaheadAuditBatchPacker {

    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
    private static final int PAIR_PROBES = 96;

    List<AuditBatch> pack(AuditSubmission submission) {
        TreeMap<Long, ArrayDeque<Transaction>> remaining = new TreeMap<>();
        for (Transaction transaction : submission.transactions()) {
            long pence = transaction.amount().abs().movePointRight(2).longValueExact();
            if (pence > CAPACITY) {
                throw new InvalidAuditSubmissionException("A transaction exceeds the batch capacity");
            }
            remaining.computeIfAbsent(pence, key -> new ArrayDeque<>()).addLast(transaction);
        }
        // A fixed seed makes identical input occurrences produce repeatable
        // candidate choices. Only exact certification establishes optimality.
        SplittableRandom probes = new SplittableRandom(0);
        List<AuditBatch> batches = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ExactAuditBatchPacker.checkInterrupted();
            List<Transaction> transactions = new ArrayList<>();
            long largest = remaining.lastKey();
            transactions.add(remove(remaining, largest));
            long free = CAPACITY - largest;
            while (!remaining.isEmpty() && remaining.firstKey() <= free) {
                ExactAuditBatchPacker.checkInterrupted();
                Choice choice = choose(remaining, free, probes);
                transactions.add(remove(remaining, choice.first));
                if (choice.second > 0) {
                    transactions.add(remove(remaining, choice.second));
                }
                free -= choice.first + choice.second;
            }
            batches.add(new AuditBatch(transactions));
        }
        return List.copyOf(batches);
    }

    private Choice choose(TreeMap<Long, ArrayDeque<Transaction>> remaining, long free, SplittableRandom probes) {
        long first = remaining.floorKey(free);
        long second = 0;
        long best = first;
        long minimum = remaining.firstKey();
        long maximum = Math.min(remaining.lastKey(), free - minimum);
        for (int probe = 0; probe < PAIR_PROBES && best < free && maximum >= minimum; probe++) {
            long candidate = probe == 0 ? Math.min(maximum, free / 2) : probes.nextLong(minimum, maximum + 1);
            Long left = remaining.floorKey(candidate);
            if (left == null) {
                continue;
            }
            Long right = remaining.floorKey(free - left);
            if (right != null && left.equals(right) && remaining.get(left).size() < 2) {
                right = remaining.lowerKey(right);
            }
            if (right != null && left + right > best) {
                first = left;
                second = right;
                best = left + right;
            }
        }
        return new Choice(first, second);
    }

    private Transaction remove(TreeMap<Long, ArrayDeque<Transaction>> remaining, long pence) {
        ArrayDeque<Transaction> occurrences = remaining.get(pence);
        Transaction transaction = occurrences.removeFirst();
        if (occurrences.isEmpty()) {
            remaining.remove(pence);
        }
        return transaction;
    }

    private record Choice(long first, long second) {
    }
}
