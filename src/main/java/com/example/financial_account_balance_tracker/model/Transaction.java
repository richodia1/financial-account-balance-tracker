package com.example.financial_account_balance_tracker.model;

import com.example.financial_account_balance_tracker.enums.TransactionType;
import com.example.financial_account_balance_tracker.exception.InvalidTransactionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * An immutable GBP transaction. Credits are positive and debits are negative.
 * Amounts are normalized to two decimal places without rounding.
 */
public record Transaction(UUID id, BigDecimal amount) {

    public Transaction {
        if (id == null) {
            throw new InvalidTransactionException("Transaction ID must not be null");
        }
        if (amount == null) {
            throw new InvalidTransactionException("Transaction amount must not be null");
        }

        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidTransactionException(
                    "Transaction amount must be representable in whole pence", exception);
        }

        if (amount.signum() == 0) {
            throw new InvalidTransactionException("Transaction amount must not be zero");
        }
    }

    /**
     * Derives the type from the signed amount so the two cannot contradict.
     */
    public TransactionType type() {
        return amount.signum() > 0 ? TransactionType.CREDIT : TransactionType.DEBIT;
    }
}
