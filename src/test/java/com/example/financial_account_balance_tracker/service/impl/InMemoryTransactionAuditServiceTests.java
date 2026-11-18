package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.exception.AuditUnavailableException;
import com.example.financial_account_balance_tracker.exception.InvalidTransactionException;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransactionAuditServiceTests {

    @Test
    void handsOffExactlyOneThousandTransactionsAndKeepsTheNextPartialWindow() {
        List<AuditSubmission> submissions = new ArrayList<>();
        InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(1000, submissions::add);
        for (int i = 1; i <= 999; i++) {
            collector.record(transaction(i));
        }
        assertTrue(submissions.isEmpty());
        assertEquals(999, collector.pendingTransactionCount());

        collector.record(transaction(1000));

        assertEquals(1, submissions.size());
        assertEquals(1000, submissions.get(0).transactions().size());
        assertEquals(transaction(1), submissions.get(0).transactions().get(0));
        assertEquals(transaction(1000), submissions.get(0).transactions().get(999));
        assertEquals(0, collector.pendingTransactionCount());

        collector.record(transaction(1001));
        assertEquals(1, submissions.size());
        assertEquals(1, collector.pendingTransactionCount());
    }

    @Test
    void createsIndependentOrderedSubmissionsWithoutOverlappingTransactions() {
        List<AuditSubmission> submissions = new ArrayList<>();
        InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(1000, submissions::add);
        for (int i = 1; i <= 2001; i++) {
            collector.record(transaction(i));
        }

        assertEquals(2, submissions.size());
        for (int window = 0; window < 2; window++) {
            AuditSubmission submission = submissions.get(window);
            assertEquals(window + 1, submission.sequence());
            assertEquals(1000, submission.transactions().size());
            for (int item = 0; item < 1000; item++) {
                assertEquals(transaction(window * 1000 + item + 1), submission.transactions().get(item));
            }
        }
        assertEquals(1, collector.pendingTransactionCount());
    }

    @Test
    void rejectedHandoffCanBeRetriedWithoutLosingThePartialWindowOrSequence() {
        AtomicBoolean accept = new AtomicBoolean(false);
        List<AuditSubmission> submissions = new ArrayList<>();
        RejectedExecutionException cause = new RejectedExecutionException("Queue full");
        InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(3, submission -> {
            if (!accept.get()) {
                throw cause;
            }
            submissions.add(submission);
        });
        collector.record(transaction(1));
        collector.record(transaction(2));

        assertSame(cause, assertThrows(AuditUnavailableException.class, () -> collector.record(transaction(3))).getCause());
        assertEquals(2, collector.pendingTransactionCount());
        assertTrue(submissions.isEmpty());

        accept.set(true);
        collector.record(transaction(3));
        assertEquals(1, submissions.size());
        assertEquals(1, submissions.get(0).sequence());
        assertEquals(List.of(transaction(1), transaction(2), transaction(3)), submissions.get(0).transactions());
        assertEquals(0, collector.pendingTransactionCount());
    }

    @Test
    void supportsOneHundredThousandTransactionWindows() {
        List<AuditSubmission> submissions = new ArrayList<>();
        InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(100000, submissions::add);
        for (int i = 1; i <= 100000; i++) {
            collector.record(transaction(i));
        }

        assertEquals(1, submissions.size());
        assertEquals(100000, submissions.get(0).transactions().size());
        assertEquals(0, collector.pendingTransactionCount());
    }

    @Test
    void rejectsNullWithoutChangingThePartialWindow() {
        InMemoryTransactionAuditService collector = new InMemoryTransactionAuditService(1000, submission -> {});
        collector.record(transaction(1));

        assertThrows(InvalidTransactionException.class, () -> collector.record(null));
        assertEquals(1, collector.pendingTransactionCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidSubmissionSizes(int size) {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryTransactionAuditService(size, submission -> {}));
    }

    private static Transaction transaction(long id) {
        return new Transaction(new UUID(0, id), new BigDecimal(id % 2 == 0 ? "-200.00" : "200.00"));
    }
}
