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
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPackingQualityTests {

    private static final long CAPACITY = AuditBatch.MAX_ABSOLUTE_VALUE.movePointRight(2).longValueExact();
    private final BestFitDecreasingAuditBatchingService service = new BestFitDecreasingAuditBatchingService();

    @ParameterizedTest
    @MethodSource("knownOptima")
    void checksPackingAgainstIndependentlyKnownMinimums(long[] tenThousandPoundUnits, int minimum) {
        long[] pennies = Arrays.stream(tenThousandPoundUnits).map(value -> value * 1000000).toArray();
        int exactMinimum = exactMinimum(pennies);
        var result = service.pack(submission(pennies));

        assertThat(exactMinimum).isEqualTo(minimum);
        assertThat(result.batchCountLowerBound()).isLessThanOrEqualTo(exactMinimum);
        assertThat(result.batches().size()).isGreaterThanOrEqualTo(exactMinimum);
        if (result.optimalityProven()) {
            assertThat(result.batches()).hasSize(exactMinimum);
        }
    }

    @Test
    void validatesLowerBoundsAndOptimalityClaimsAgainstAnExactSmallInstanceOracle() {
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
            assertThat(result.batches().size()).as("Feasible batch count for sample %s", sample)
                    .isGreaterThanOrEqualTo(minimum);
            assertThat(!result.optimalityProven() || result.batches().size() == minimum)
                    .as("Optimality claim for sample %s", sample).isTrue();
        }
    }

    private static Stream<Arguments> knownOptima() {
        return Stream.of(
                Arguments.of(new long[] {44, 25, 20, 39, 34, 27}, 2),
                Arguments.of(new long[] {34, 34, 34, 34, 34}, 3),
                Arguments.of(new long[] {50, 50, 50, 50}, 2),
                Arguments.of(new long[] {49, 41, 40, 35, 25, 10}, 2));
    }

    /**
     * Exhaustive subset dynamic programming, independent of best-fit decreasing.
     * For each subset, keep the fewest bins and then the lightest last bin.
     * Considering each item as the last one explores every possible ordering.
     * O(N * 2^N) time and O(2^N) space limit this oracle to small test inputs.
     */
    private static int exactMinimum(long[] pennies) {
        if (pennies.length < 1 || pennies.length > 16) {
            throw new IllegalArgumentException("The exact test oracle accepts one to sixteen transactions");
        }
        int states = 1 << pennies.length;
        int[] bins = new int[states];
        long[] lastLoad = new long[states];
        Arrays.fill(bins, Integer.MAX_VALUE);
        Arrays.fill(lastLoad, Long.MAX_VALUE);
        bins[0] = 1;
        lastLoad[0] = 0;
        for (int subset = 1; subset < states; subset++) {
            for (int item = 0; item < pennies.length; item++) {
                int bit = 1 << item;
                if ((subset & bit) == 0) {
                    continue;
                }
                int previous = subset ^ bit;
                boolean fits = lastLoad[previous] + pennies[item] <= CAPACITY;
                int candidateBins = bins[previous] + (fits ? 0 : 1);
                long candidateLoad = fits ? lastLoad[previous] + pennies[item] : pennies[item];
                if (candidateBins < bins[subset]
                        || (candidateBins == bins[subset] && candidateLoad < lastLoad[subset])) {
                    bins[subset] = candidateBins;
                    lastLoad[subset] = candidateLoad;
                }
            }
        }
        return bins[states - 1];
    }

    private static AuditSubmission submission(long[] pennies) {
        List<Transaction> transactions = new ArrayList<>(pennies.length);
        for (int i = 0; i < pennies.length; i++) {
            transactions.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pennies[i] : -pennies[i], 2)));
        }
        return new AuditSubmission(1, transactions);
    }
}
