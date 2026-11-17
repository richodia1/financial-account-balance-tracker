package com.example.financial_account_balance_tracker.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditSubmissionTests {

    @Test
    void ownsAnImmutableSnapshotOfItsTransactions() {
        Transaction transaction = new Transaction(new UUID(0, 1), new BigDecimal("200.00"));
        List<Transaction> source = new ArrayList<>(List.of(transaction));
        AuditSubmission submission = new AuditSubmission(1, source);

        source.clear();

        assertEquals(List.of(transaction), submission.transactions());
        assertThrows(UnsupportedOperationException.class, () -> submission.transactions().clear());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void requiresAPositiveSequence(long sequence) {
        Transaction transaction = new Transaction(new UUID(0, 1), new BigDecimal("200.00"));
        assertThrows(IllegalArgumentException.class, () -> new AuditSubmission(sequence, List.of(transaction)));
    }

    @Test
    void rejectsEmptySubmissions() {
        assertThrows(IllegalArgumentException.class, () -> new AuditSubmission(1, List.of()));
    }

    @Test
    void rejectsMissingTransactions() {
        assertThrows(NullPointerException.class, () -> new AuditSubmission(1, null));
    }
}
