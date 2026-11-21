package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.AuditBatch;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LookaheadAuditBatchPackerTests {

    @Test
    void closesTheSeededHundredThousandTransactionGapAndPreservesEveryOccurrence() {
        SplittableRandom random = new SplittableRandom(62831);
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 100000; i++) {
            long pence = random.nextLong(20000, 50000001);
            transactions.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pence : -pence, 2)));
        }
        AuditSubmission submission = new AuditSubmission(1, transactions);
        var initial = new BestFitDecreasingAuditBatchingService().pack(submission);
        assertThat(initial.batches().size()).isEqualTo(24970);

        List<AuditBatch> result = new LookaheadAuditBatchPacker().pack(submission);

        assertThat(result.size()).isEqualTo(24969);
        assertThat(result.size()).isEqualTo(initial.batchCountLowerBound());
        assertPreserved(transactions, result);
    }

    @Test
    void preservesRepeatedObjectsAndSignsWithoutUsingIdsAsDeduplicationKeys() {
        Transaction repeated = new Transaction(new UUID(0, 1), new BigDecimal("-450000.00"));
        List<Transaction> transactions = new ArrayList<>(java.util.Collections.nCopies(4, repeated));
        for (int i = 0; i < 3; i++) {
            transactions.add(new Transaction(new UUID(0, i + 2), new BigDecimal("60000.00")));
        }

        List<AuditBatch> result = new LookaheadAuditBatchPacker().pack(new AuditSubmission(1, transactions));

        assertThat(result).hasSize(3);
        assertPreserved(transactions, result);
    }

    @Test
    void pairsEqualMagnitudesOnlyWhenTwoOriginalOccurrencesExist() {
        Transaction credit = new Transaction(new UUID(0, 1), new BigDecimal("500000.00"));
        Transaction debit = new Transaction(new UUID(0, 2), new BigDecimal("-500000.00"));
        List<Transaction> input = List.of(credit, debit, new Transaction(new UUID(0, 3), new BigDecimal("200.00")));

        List<AuditBatch> result = new LookaheadAuditBatchPacker().pack(new AuditSubmission(1, input));

        assertThat(result).hasSize(2);
        assertPreserved(input, result);
    }

    private static void assertPreserved(List<Transaction> transactions, List<AuditBatch> batches) {
        HashMap<Transaction, Integer> expected = new HashMap<>();
        HashMap<Transaction, Integer> actual = new HashMap<>();
        transactions.forEach(t -> expected.merge(t, 1, Integer::sum));
        for (AuditBatch batch : batches) {
            assertThat(batch.totalValueOfAllTransactions()).isPositive().isLessThanOrEqualTo(AuditBatch.MAX_ABSOLUTE_VALUE);
            batch.transactions().forEach(t -> actual.merge(t, 1, Integer::sum));
        }
        assertThat(actual).isEqualTo(expected);
    }
}
