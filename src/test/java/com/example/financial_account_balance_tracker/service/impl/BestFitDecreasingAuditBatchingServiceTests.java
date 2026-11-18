package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BestFitDecreasingAuditBatchingServiceTests {

    private final BestFitDecreasingAuditBatchingService service = new BestFitDecreasingAuditBatchingService();

    @Test
    void fillsTheExactLimitWithoutNettingCreditsAgainstDebits() {
        AuditBatchingResult result = service.pack(submission("500000", "-500000", "500000", "-500000"));

        assertThat(result.batches()).hasSize(2).allSatisfy(batch -> {
            assertThat(batch.countOfTransactions()).isEqualTo(2);
            assertThat(batch.totalValueOfAllTransactions()).isEqualTo(new BigDecimal("1000000.00"));
        });
        assertThat(result.batchCountLowerBound()).isEqualTo(2);
        assertThat(result.optimalityProven()).isTrue();
    }

    @Test
    void opensAnotherBatchForOnePennyBeyondTheLimit() {
        AuditBatchingResult result = service.pack(submission("500000", "-500000", "0.01"));

        assertThat(result.batches()).hasSize(2);
        assertThat(result.batches().get(1).totalValueOfAllTransactions()).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void reusesEarlierBatchesInsteadOfOnlyFillingTheMostRecentBatch() {
        AuditBatchingResult result = service.pack(submission("490000", "410000", "400000", "350000", "250000", "100000"));

        // Two full batches prove that no better packing is possible.
        assertThat(result.batches()).hasSize(2);
        assertThat(result.batches().get(0).transactions()).extracting(Transaction::amount)
                .containsExactly(new BigDecimal("490000.00"), new BigDecimal("410000.00"), new BigDecimal("100000.00"));
        assertThat(result.batches().get(1).transactions()).extracting(Transaction::amount)
                .containsExactly(new BigDecimal("400000.00"), new BigDecimal("350000.00"), new BigDecimal("250000.00"));
        assertThat(result.optimalityProven()).isTrue();
    }

    @Test
    void selectsTheTightestFitWhenSeveralEarlierBatchesHaveRoom() {
        AuditBatchingResult result = service.pack(submission("500000", "490000", "480000", "470000", "60000", "40000"));

        assertThat(result.batches().get(1).transactions()).extracting(Transaction::amount)
                .containsExactly(new BigDecimal("480000.00"), new BigDecimal("470000.00"), new BigDecimal("40000.00"));
    }

    @Test
    void preservesRepeatedOccurrencesEvenWhenIdsAndAmountsAreIdentical() {
        Transaction repeated = new Transaction(new UUID(0, 1), new BigDecimal("500000.00"));
        AuditSubmission input = new AuditSubmission(1, List.of(repeated, repeated, repeated, repeated, repeated));
        AuditBatchingResult result = service.pack(input);

        assertThat(flatten(result)).containsExactly(repeated, repeated, repeated, repeated, repeated);
        assertThat(result.batches()).extracting(AuditBatch::countOfTransactions).containsExactly(2, 2, 1);
    }

    @Test
    void equalMagnitudesAreDeterministicAndTheOriginalSubmissionIsNotReordered() {
        AuditSubmission input = submission("200", "-500000", "500000", "-200");
        List<Transaction> original = List.copyOf(input.transactions());
        AuditBatchingResult first = service.pack(input);
        AuditBatchingResult second = service.pack(input);

        assertThat(input.transactions()).containsExactlyElementsOf(original);
        assertThat(flatten(first)).containsExactly(original.get(1), original.get(2), original.get(0), original.get(3));
        assertThat(second.batches().stream().map(AuditBatch::transactions).toList())
                .isEqualTo(first.batches().stream().map(AuditBatch::transactions).toList());
    }

    @Test
    void doesNotFalselyClaimOptimalityForAKnownHeuristicCounterexample() {
        AuditBatchingResult result = service.pack(submission("440000", "250000", "200000", "390000", "340000", "270000"));

        // A two-batch solution is [440000, 340000, 200000] and [390000, 270000, 250000].
        assertThat(result.batches()).hasSize(3);
        assertThat(result.batchCountLowerBound()).isEqualTo(2);
        assertThat(result.optimalityProven()).isFalse();
    }

    @Test
    void aLowerBoundGapDoesNotByItselfProveThePackingIsSuboptimal() {
        AuditBatchingResult result = service.pack(submission("340000", "340000", "340000", "340000", "340000"));

        // Only two of these amounts fit per batch: three batches are necessary,
        // although the simple volume bound is two.
        assertThat(result.batches()).hasSize(3);
        assertThat(result.batchCountLowerBound()).isEqualTo(2);
        assertThat(result.optimalityProven()).isFalse();
    }

    @Test
    void strengthensTheVolumeBoundForTransactionsLargerThanHalfTheCapacity() {
        AuditBatchingResult result = service.pack(submission("600000", "-600000", "600000"));

        assertThat(result.batches()).hasSize(3);
        assertThat(result.batchCountLowerBound()).isEqualTo(3);
        assertThat(result.optimalityProven()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000000.01", "-1000000.01", "999999999999999999999999999999"})
    void rejectsAnIndividualTransactionThatCannotFit(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.pack(submission(amount)));
    }

    @Test
    void rejectsAMissingSubmission() {
        assertThatExceptionOfType(InvalidAuditSubmissionException.class).isThrownBy(() -> service.pack(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 100000})
    void preservesEveryTransactionAndExactTotalsForLargeSeededInputs(int size) {
        SplittableRandom random = new SplittableRandom(62831);
        List<Transaction> transactions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long pennies = random.nextLong(20000, 50000001);
            transactions.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)));
        }

        AuditBatchingResult result = service.pack(new AuditSubmission(1, transactions));
        List<Transaction> packed = flatten(result);

        assertThat(packed).hasSize(size);
        var packedById = packed.stream().collect(Collectors.toMap(Transaction::id, Function.identity()));
        var expectedById = transactions.stream().collect(Collectors.toMap(Transaction::id, Function.identity()));
        assertThat(packedById.equals(expectedById)).as("Every original transaction is preserved exactly").isTrue();
        BigDecimal expectedTotal = transactions.stream().map(transaction -> transaction.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.batches()).allSatisfy(batch -> {
            assertThat(batch.totalValueOfAllTransactions()).isPositive().isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
            BigDecimal expectedBatchTotal = batch.transactions().stream().map(transaction -> transaction.amount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(batch.totalValueOfAllTransactions()).isEqualByComparingTo(expectedBatchTotal);
        });
        assertThat(result.batches().stream().map(AuditBatch::totalValueOfAllTransactions)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(expectedTotal);
        assertThat(result.batchCountLowerBound()).isLessThanOrEqualTo(result.batches().size());
    }

    private static List<Transaction> flatten(AuditBatchingResult result) {
        return result.batches().stream().flatMap(batch -> batch.transactions().stream()).toList();
    }

    private static AuditSubmission submission(String... amounts) {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            transactions.add(new Transaction(new UUID(0, i), new BigDecimal(amounts[i])));
        }
        return new AuditSubmission(1, transactions);
    }
}
