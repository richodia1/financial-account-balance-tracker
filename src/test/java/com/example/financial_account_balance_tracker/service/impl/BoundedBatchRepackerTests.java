package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedBatchRepackerTests {

    @Test
    void backtracksPastTheGreedyPackingAndRetainsRepeatedOccurrences() {
        List<Transaction> input = new ArrayList<>(List.of(transaction("440000"), transaction("390000"),
                transaction("340000"), transaction("270000"), transaction("250000"), transaction("200000")));
        Transaction repeated = transaction("-200");
        input.addAll(Collections.nCopies(20, repeated));
        List<Transaction> original = List.copyOf(input);

        var result = new BoundedBatchRepacker().repack(input, 2).orElseThrow();

        assertThat(result).hasSize(2);
        assertPreserved(input, result);
        assertThat(input).containsExactlyElementsOf(original);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsNoReplacementWhenCapacityMakesTheTargetImpossible() {
        assertThat(new BoundedBatchRepacker().repack(List.of(transaction("500000"), transaction("500000"),
                transaction("0.01")), 1)).isEmpty();
    }

    @Test
    void handlesInfeasibleCombinationsEvenWhenTheVolumeBoundFits() {
        assertThat(new BoundedBatchRepacker().repack(Collections.nCopies(5, transaction("340000")), 2)).isEmpty();
    }

    @Test
    void budgetExhaustionReturnsNoReplacementAndDoesNotAffectTheNextCall() {
        BoundedBatchRepacker limited = new BoundedBatchRepacker(2);
        List<Transaction> input = List.of(transaction("440000"), transaction("390000"), transaction("340000"),
                transaction("270000"), transaction("250000"), transaction("200000"));

        assertThat(limited.repack(input, 2)).isEmpty();
        assertThat(limited.repack(List.of(transaction("200")), 1)).isPresent();
    }

    @Test
    void acceptsTheFullItemLimitWithoutLosingAnyOccurrences() {
        List<Transaction> input = Collections.nCopies(128, transaction("200"));

        var result = new BoundedBatchRepacker().repack(input, 1).orElseThrow();

        assertThat(result).hasSize(1);
        assertPreserved(input, result);
    }

    @Test
    void rejectsInputsOutsideTheItemAndAmountBounds() {
        BoundedBatchRepacker repacker = new BoundedBatchRepacker();
        assertThatIllegalArgumentException().isThrownBy(() -> repacker.repack(List.of(), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> repacker.repack(
                Collections.nCopies(129, transaction("200")), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> repacker.repack(List.of(transaction("1000000.01")), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new BoundedBatchRepacker(-1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 6})
    void rejectsTargetsOutsideTheBatchLimit(int target) {
        assertThatIllegalArgumentException().isThrownBy(() -> new BoundedBatchRepacker()
                .repack(List.of(transaction("200")), target));
    }

    private static void assertPreserved(List<Transaction> input, List<AuditBatch> batches) {
        HashMap<Transaction, Integer> expected = new HashMap<>();
        HashMap<Transaction, Integer> actual = new HashMap<>();
        input.forEach(transaction -> expected.merge(transaction, 1, Integer::sum));
        batches.forEach(batch -> {
            assertThat(batch.totalValueOfAllTransactions()).isPositive().isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
            batch.transactions().forEach(transaction -> actual.merge(transaction, 1, Integer::sum));
        });
        assertThat(actual.equals(expected)).isTrue();
    }

    private static Transaction transaction(String amount) {
        return new Transaction(UUID.randomUUID(), new BigDecimal(amount));
    }
}
