package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.AuditProcessingException;
import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactAuditBatchPackerTests {

    @Test
    void provesInfeasibilityAboveTheVolumeAndCardinalityBounds() {
        AuditSubmission input = hardSubmission();
        var initial = new BestFitDecreasingAuditBatchingService().pack(input);
        assertThat(initial.batchCountLowerBound()).isEqualTo(2);
        assertThat(initial.batches()).hasSize(3);

        assertThat(new ExactAuditBatchPacker().packInto(input.transactions(), 2)).isEmpty();
        var feasible = new ExactAuditBatchPacker().packInto(input.transactions(), 3).orElseThrow();

        assertThat(feasible).hasSize(3);
        assertPreserved(input.transactions(), feasible);
    }

    @Test
    void completeSearchCertifiesTheDefaultResultAfterLocalSearchIsDisabled() {
        AuditSubmission input = hardSubmission();

        var result = new OptimizingAuditBatchingService(new BoundedBatchRepacker(0)).pack(input);

        assertThat(result.batches()).hasSize(3);
        assertThat(result.batchCountLowerBound()).isEqualTo(3);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(input.transactions(), result.batches());
    }

    @Test
    void certifiesACompleteWindowWhoseMinimumIsAboveBothInitialBounds() {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String amount = i < 972 ? "500000.00" : i < 976 ? "450000.00"
                    : i < 979 ? "-60000.00" : "200.00";
            transactions.add(new Transaction(new UUID(0, i), new BigDecimal(amount)));
        }
        AuditSubmission input = new AuditSubmission(1, transactions);
        assertThat(new BestFitDecreasingAuditBatchingService().pack(input).batchCountLowerBound()).isEqualTo(488);

        var result = new OptimizingAuditBatchingService(new BoundedBatchRepacker(0)).pack(input);

        // 976 large items force two per bin in a hypothetical 488-bin solution.
        // Only two 450k/450k pairs can accommodate a 60k item, leaving the third
        // 60k item without a position. Hence at least 489 bins are required.
        assertThat(result.batches()).hasSize(489);
        assertThat(result.batchCountLowerBound()).isEqualTo(489);
        assertThat(result.optimalityProven()).isTrue();
        assertPreserved(transactions, result.batches());
    }

    @Test
    void iterativeSearchHandlesOneHundredThousandOccurrencesWithoutRecursion() {
        Transaction repeated = new Transaction(new UUID(0, 1), new BigDecimal("-200.00"));
        List<Transaction> transactions = Collections.nCopies(100000, repeated);

        var result = new ExactAuditBatchPacker().packInto(transactions, 20).orElseThrow();

        assertThat(result).hasSize(20);
        assertPreserved(transactions, result);
    }

    @Test
    void cancellationThrowsInsteadOfReturningAnUnprovenPackingAndPreservesTheInterrupt() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> new ExactAuditBatchPacker().packInto(hardSubmission().transactions(), 3))
                    .isInstanceOf(AuditProcessingException.class).hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static AuditSubmission hardSubmission() {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            String amount = i < 4 ? "450000.00" : i < 7 ? "-60000.00" : "200.00";
            transactions.add(new Transaction(new UUID(0, i), new BigDecimal(amount)));
        }
        // Four 450k items force two per bin if only two bins are used. Their
        // 100k residuals can hold at most two of the three indivisible 60k items.
        return new AuditSubmission(1, transactions);
    }

    private static void assertPreserved(List<Transaction> input, List<AuditBatch> batches) {
        HashMap<Transaction, Integer> expected = new HashMap<>();
        HashMap<Transaction, Integer> actual = new HashMap<>();
        input.forEach(t -> expected.merge(t, 1, Integer::sum));
        batches.forEach(batch -> {
            assertThat(batch.totalValueOfAllTransactions()).isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
            batch.transactions().forEach(t -> actual.merge(t, 1, Integer::sum));
        });
        assertThat(actual).isEqualTo(expected);
    }
}
