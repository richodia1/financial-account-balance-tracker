package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OptimizingAuditBatchingServiceTests {

    private final OptimizingAuditBatchingService service = new OptimizingAuditBatchingService();

    @Test
    void repairsTheSixTransactionCounterexampleAndProvesItsMinimum() {
        AuditSubmission input = submission("440000", "250000", "200000", "390000", "340000", "270000");

        var result = service.pack(input);

        assertThat(result.batches()).hasSize(2);
        assertThat(result.batchCountLowerBound()).isEqualTo(2);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input, result.batches());
    }

    @Test
    void wholeSubmissionSearchCanProveAMinimumAboveTheOriginalVolumeBound() {
        var result = service.pack(submission("340000", "340000", "340000", "340000", "340000"));

        assertThat(result.batches()).hasSize(3);
        assertThat(result.batchCountLowerBound()).isEqualTo(3);
        assertThat(result.optimalityProven()).isTrue();
    }

    @Test
    void exactSearchAtTheEighteenTransactionLimitRetainsEveryOccurrence() {
        List<Transaction> transactions = new ArrayList<>(submission(
                "440000", "250000", "200000", "390000", "340000", "270000").transactions());
        Transaction repeated = new Transaction(new UUID(1, 1), new BigDecimal("-500000.00"));
        for (int i = 0; i < 12; i++) {
            transactions.add(repeated);
        }
        AuditSubmission input = new AuditSubmission(1, transactions);

        var result = service.pack(input);

        assertThat(result.batches()).hasSize(8);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input, result.batches());
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 100000})
    void largeWindowsPreserveEveryTransactionAndNeverIncreaseTheInitialBatchCount(int size) {
        SplittableRandom random = new SplittableRandom(62831);
        List<Transaction> transactions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long pennies = random.nextLong(20000, 50000001);
            transactions.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)));
        }
        AuditSubmission input = new AuditSubmission(1, transactions);
        var baseline = new BestFitDecreasingAuditBatchingService().pack(input);

        var result = service.pack(input);

        assertThat(result.batches().size()).isLessThanOrEqualTo(baseline.batches().size());
        assertThat(result.batchCountLowerBound()).isEqualTo(result.batches().size());
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input, result.batches());
        assertThat(input.transactions()).isEqualTo(transactions);
    }

    @Test
    void certifiesTheGlobalMinimumWhenTheOriginalVolumeBoundIsWeak() {
        // All 25 items exceed one third of capacity, so at most two fit per bin.
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            transactions.add(new Transaction(new UUID(0, i), new BigDecimal("500000.00")));
        }
        transactions.addAll(submission("340000", "340000", "340000", "340000", "340000").transactions());
        AuditSubmission input = new AuditSubmission(1, transactions);

        var result = service.pack(input);

        assertThat(result.batches()).hasSize(13);
        assertThat(result.batchCountLowerBound()).isEqualTo(13);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input, result.batches());
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 100000})
    void repairsTheLargerUnderfilledCandidateAndReachesTheGlobalBound(int size) {
        AuditSubmission input = extendedCounterexample(size);

        var result = service.pack(input);

        assertThat(result.batches()).hasSize(size / 2 - 11);
        assertThat(result.batchCountLowerBound()).isEqualTo(size / 2 - 11);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input, result.batches());
    }

    @Test
    void completeCertificationFinishesWhenTheBoundedRepairBudgetIsExhausted() {
        AuditSubmission input = extendedCounterexample(1000);
        OptimizingAuditBatchingService limited = new OptimizingAuditBatchingService(new BoundedBatchRepacker(0));

        var result = limited.pack(input);

        assertThat(result.batches()).hasSize(489);
        assertThat(result.batchCountLowerBound()).isEqualTo(489);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input, result.batches());
    }

    @Test
    void exactHelperRejectsInputsBeyondItsMemoryBound() {
        Transaction transaction = new Transaction(new UUID(0, 1), new BigDecimal("200.00"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SmallSetExactBatchPacker().pack(
                java.util.Collections.nCopies(19, transaction)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SmallSetExactBatchPacker().pack(List.of()));
    }

    @Test
    void oversizedTransactionsAreRejectedByTheInitialValidation() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.pack(submission("1000000.01")));
    }

    private static void assertPreserved(AuditSubmission input, List<AuditBatch> batches) {
        HashMap<Transaction, Integer> expected = new HashMap<>();
        HashMap<Transaction, Integer> actual = new HashMap<>();
        input.transactions().forEach(transaction -> expected.merge(transaction, 1, Integer::sum));
        for (AuditBatch batch : batches) {
            assertThat(batch.totalValueOfAllTransactions()).isPositive().isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
            batch.transactions().forEach(transaction -> actual.merge(transaction, 1, Integer::sum));
        }
        assertThat(actual.equals(expected)).as("All original occurrences survive exactly").isTrue();
    }

    private static AuditSubmission submission(String... amounts) {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            transactions.add(new Transaction(new UUID(0, i), new BigDecimal(amounts[i])));
        }
        return new AuditSubmission(1, transactions);
    }

    private static AuditSubmission extendedCounterexample(int size) {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < size - 26; i++) {
            transactions.add(new Transaction(new UUID(1, i), new BigDecimal("500000.00")));
        }
        transactions.addAll(submission("440000", "250000", "200000", "390000", "340000", "270000").transactions());
        for (int i = 0; i < 20; i++) {
            transactions.add(new Transaction(new UUID(2, i), new BigDecimal("200.00")));
        }
        return new AuditSubmission(1, transactions);
    }
}
