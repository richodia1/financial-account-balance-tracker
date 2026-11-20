package com.example.financial_account_balance_tracker.model;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;

import java.util.List;

/**
 * An immutable, ordered window of transactions prepared for audit processing.
 */
public record AuditSubmission(long sequence, List<Transaction> transactions) {

    public AuditSubmission {
        if (sequence < 1) {
            throw new InvalidAuditSubmissionException("Submission sequence must be positive");
        }
        try {
            transactions = List.copyOf(transactions);
        } catch (NullPointerException exception) {
            throw new InvalidAuditSubmissionException("Audit transactions must be present and must not contain nulls", exception);
        }
        if (transactions.isEmpty()) {
            throw new InvalidAuditSubmissionException("Audit submissions must contain transactions");
        }
    }
}
