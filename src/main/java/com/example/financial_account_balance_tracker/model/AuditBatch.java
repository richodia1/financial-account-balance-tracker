package com.example.financial_account_balance_tracker.model;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;

import java.math.BigDecimal;
import java.util.List;

/** An immutable batch whose capacity counts credits and debits by magnitude. */
public final class AuditBatch {

    public static final BigDecimal MAX_ABSOLUTE_VALUE = new BigDecimal("1000000.00");

    private final List<Transaction> transactions;
    private final BigDecimal totalValueOfAllTransactions;

    public AuditBatch(List<Transaction> transactions) {
        try {
            this.transactions = List.copyOf(transactions);
        } catch (NullPointerException exception) {
            throw new InvalidAuditSubmissionException("Batch transactions must be present and must not contain nulls", exception);
        }
        if (this.transactions.isEmpty()) {
            throw new InvalidAuditSubmissionException("Audit batches must contain transactions");
        }
        this.totalValueOfAllTransactions = this.transactions.stream()
                .map(transaction -> transaction.amount().abs())
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        if (totalValueOfAllTransactions.compareTo(MAX_ABSOLUTE_VALUE) > 0) {
            throw new InvalidAuditSubmissionException("Audit batch absolute value exceeds GBP 1,000,000");
        }
    }

    public List<Transaction> transactions() {
        return transactions;
    }

    public BigDecimal totalValueOfAllTransactions() {
        return totalValueOfAllTransactions;
    }

    public int countOfTransactions() {
        return transactions.size();
    }
}
