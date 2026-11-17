package com.example.financial_account_balance_tracker.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditBatchTests {

    @Test
    void oppositeSignsUseAbsoluteCapacityAndMayReachTheLimitExactly() {
        AuditBatch batch = new AuditBatch(List.of(transaction("500000"), transaction("-500000")));

        assertThat(batch.totalValueOfAllTransactions()).isEqualTo(new BigDecimal("1000000.00"));
        assertThat(batch.countOfTransactions()).isEqualTo(2);
    }

    @Test
    void rejectsOnePennyOverCapacityEvenWhenTheSignedTotalIsSmall() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditBatch(List.of(
                transaction("500000.00"), transaction("-500000.00"), transaction("0.01"))));
    }

    @Test
    void copiesTheInputAndExposesAnUnmodifiableSnapshot() {
        List<Transaction> input = new ArrayList<>(List.of(transaction("200.01")));
        AuditBatch batch = new AuditBatch(input);
        input.clear();

        assertThat(batch.transactions()).hasSize(1);
        assertThat(batch.totalValueOfAllTransactions()).isEqualTo(new BigDecimal("200.01"));
        assertThatThrownBy(() -> batch.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingOrEmptyTransactions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditBatch(List.of()));
        assertThatNullPointerException().isThrownBy(() -> new AuditBatch(null));
        List<Transaction> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatNullPointerException().isThrownBy(() -> new AuditBatch(withNull));
    }

    @Test
    void packingResultCopiesItsListAndOnlyProvesOptimalityAtTheLowerBound() {
        AuditBatch batch = new AuditBatch(List.of(transaction("500000")));
        List<AuditBatch> input = new ArrayList<>(List.of(batch, batch));
        AuditBatchingResult result = new AuditBatchingResult(input, 1);
        input.clear();

        assertThat(result.batches()).hasSize(2);
        assertThat(result.optimalityProven()).isFalse();
        assertThat(new AuditBatchingResult(result.batches(), 2).optimalityProven()).isTrue();
        assertThatThrownBy(() -> result.batches().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2})
    void rejectsInvalidLowerBounds(int lowerBound) {
        AuditBatch batch = new AuditBatch(List.of(transaction("200")));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditBatchingResult(List.of(batch), lowerBound));
    }

    @Test
    void rejectsAnEmptyPacking() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditBatchingResult(List.of(), 1));
    }

    private static Transaction transaction(String amount) {
        return new Transaction(UUID.randomUUID(), new BigDecimal(amount));
    }
}
