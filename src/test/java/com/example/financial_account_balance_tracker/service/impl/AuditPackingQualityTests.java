package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPackingQualityTests {

    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
    private final OptimizingAuditBatchingService service = new OptimizingAuditBatchingService();

    @ParameterizedTest
    @MethodSource("knownOptima")
    void checksPackingAgainstIndependentlyKnownMinimums(long[] tenThousandPoundUnits, int minimum) {
        long[] pennies = Arrays.stream(tenThousandPoundUnits).map(value -> value * 1000000).toArray();
        int exactMinimum = exactMinimum(pennies);
        var result = service.pack(submission(pennies));

        assertThat(exactMinimum).isEqualTo(minimum);
        assertThat(result.batchCountLowerBound()).isLessThanOrEqualTo(exactMinimum);
        assertThat(result.batches()).hasSize(exactMinimum);
        assertThat(result.optimalityProven()).isTrue();
    }

    @Test
    void findsTheExactMinimumForSeededSmallInputsAgainstAnIndependentAssignmentOracle() {
        SplittableRandom random = new SplittableRandom(789214);
        for (int sample = 0; sample < 32; sample++) {
            long[] pennies = new long[6 + random.nextInt(8)];
            for (int i = 0; i < pennies.length; i++) {
                pennies[i] = random.nextLong(20000, 50000001);
            }
            int minimum = exactMinimum(pennies);
            var result = service.pack(submission(pennies));

            assertThat(result.batchCountLowerBound()).as("Lower bound for sample %s", sample)
                    .isLessThanOrEqualTo(minimum);
            assertThat(result.batches()).as("Exact batch count for sample %s", sample).hasSize(minimum);
            assertThat(result.optimalityProven()).as("Optimality claim for sample %s", sample).isTrue();
        }
    }

    @Test
    void repairsTheKnownCostGapInAValidOneThousandTransactionWindow() {
        long[] pennies = new long[1000];
        Arrays.fill(pennies, 0, 994, 50000000);
        long[] remainder = {44000000, 25000000, 20000000, 39000000, 34000000, 27000000};
        System.arraycopy(remainder, 0, pennies, 994, remainder.length);
        AuditSubmission input = submission(pennies);
        var result = service.pack(input);

        // 497 full pairs plus two feasible remainder batches attain the volume bound.
        List<AuditBatch> knownOptimal = new ArrayList<>();
        List<Transaction> transactions = input.transactions();
        for (int i = 0; i < 994; i += 2) {
            knownOptimal.add(new AuditBatch(List.of(transactions.get(i), transactions.get(i + 1))));
        }
        knownOptimal.add(new AuditBatch(List.of(transactions.get(994), transactions.get(998), transactions.get(996))));
        knownOptimal.add(new AuditBatch(List.of(transactions.get(997), transactions.get(999), transactions.get(995))));

        assertThat(knownOptimal).hasSize(499);
        List<Transaction> assigned = knownOptimal.stream().flatMap(batch -> batch.transactions().stream()).toList();
        assertThat(assigned).hasSize(1000);
        assertThat(new HashSet<>(assigned).equals(new HashSet<>(transactions))).isTrue();
        assertThat(result.batchCountLowerBound()).isEqualTo(499);
        assertThat(result.batches()).hasSize(499);
        assertThat(result.optimalityProven()).isTrue();
    }

    private static Stream<Arguments> knownOptima() {
        return Stream.of(
                Arguments.of(new long[] {44, 25, 20, 39, 34, 27}, 2),
                Arguments.of(new long[] {34, 34, 34, 34, 34}, 3),
                Arguments.of(new long[] {50, 50, 50, 50}, 2),
                Arguments.of(new long[] {49, 41, 40, 35, 25, 10}, 2));
    }

    @Test
    void boundedFeasibilitySearchAgreesWithTheSeparateExactOracleOnSmallInputs() {
        SplittableRandom random = new SplittableRandom(447213);
        BoundedBatchRepacker repacker = new BoundedBatchRepacker();
        for (int sample = 0; sample < 32; sample++) {
            long[] pennies = new long[6 + random.nextInt(5)];
            for (int item = 0; item < pennies.length; item++) {
                pennies[item] = random.nextLong(20000, 50000001);
            }
            int minimum = exactMinimum(pennies);
            AuditSubmission input = submission(pennies);
            var packing = repacker.repack(input.transactions(), minimum).orElseThrow();

            assertThat(packing).hasSize(minimum);
            List<Transaction> assigned = packing.stream().flatMap(batch -> batch.transactions().stream()).toList();
            assertThat(assigned).hasSize(input.transactions().size());
            assertThat(new HashSet<>(assigned).equals(new HashSet<>(input.transactions()))).isTrue();
            if (minimum > 1) {
                assertThat(repacker.repack(input.transactions(), minimum - 1)).isEmpty();
            }
        }
    }

    @Test
    void completeIterativeSearchAgreesWithTheIndependentAssignmentOracle() {
        SplittableRandom random = new SplittableRandom(314159);
        ExactAuditBatchPacker exact = new ExactAuditBatchPacker();
        for (int sample = 0; sample < 80; sample++) {
            long[] pennies = new long[6 + random.nextInt(7)];
            for (int item = 0; item < pennies.length; item++) {
                pennies[item] = random.nextLong(20000, 50000001);
            }
            int minimum = exactMinimum(pennies);
            AuditSubmission input = submission(pennies);
            var result = exact.packInto(input.transactions(), minimum).orElseThrow();
            assertThat(result).hasSize(minimum);
            if (minimum > 1) {
                assertThat(exact.packInto(input.transactions(), minimum - 1)).isEmpty();
            }
        }
    }

    /**
     * Exhaustive bin assignments, independent of the production subset solver.
     * Symmetric equal-load bins are interchangeable. Sorting only prunes search;
     * every feasible non-equivalent assignment is still considered.
     */
    private static int exactMinimum(long[] pennies) {
        if (pennies.length < 1 || pennies.length > 16) {
            throw new IllegalArgumentException("The exact test oracle accepts one to sixteen transactions");
        }
        long[] sorted = pennies.clone();
        Arrays.sort(sorted);
        int[] best = {pennies.length};
        assign(sorted, sorted.length - 1, new long[pennies.length], 0, best);
        return best[0];
    }

    private static void assign(long[] amounts, int item, long[] loads, int used, int[] best) {
        if (used >= best[0]) {
            return;
        }
        if (item < 0) {
            best[0] = used;
            return;
        }
        HashSet<Long> seen = new HashSet<>();
        for (int bin = 0; bin < used; bin++) {
            if (loads[bin] + amounts[item] <= CAPACITY && seen.add(loads[bin])) {
                loads[bin] += amounts[item];
                assign(amounts, item - 1, loads, used, best);
                loads[bin] -= amounts[item];
            }
        }
        loads[used] = amounts[item];
        assign(amounts, item - 1, loads, used + 1, best);
        loads[used] = 0;
    }

    private static AuditSubmission submission(long[] pennies) {
        List<Transaction> transactions = new ArrayList<>(pennies.length);
        for (int i = 0; i < pennies.length; i++) {
            transactions.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pennies[i] : -pennies[i], 2)));
        }
        return new AuditSubmission(1, transactions);
    }
}
