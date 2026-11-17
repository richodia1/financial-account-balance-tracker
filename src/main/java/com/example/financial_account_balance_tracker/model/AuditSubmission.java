package com.example.financial_account_balance_tracker.model;

import java.util.List;

/**
 * An immutable, ordered window of transactions prepared for audit processing.
 */
public record AuditSubmission(long sequence, List<Transaction> transactions) {

    public AuditSubmission {
        if (sequence < 1) {
            throw new IllegalArgumentException("Submission sequence must be positive");
        }
        transactions = List.copyOf(transactions);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Audit submissions must contain transactions");
        }
    }
}
