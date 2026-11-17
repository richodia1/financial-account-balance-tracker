package com.example.financial_account_balance_tracker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable GBP transaction. Credits are positive and debits are negative.
 * Amounts are normalized to two decimal places without rounding.
 */
public record Transaction(UUID id, BigDecimal amount) {

    public Transaction {
        Objects.requireNonNull(id, "Transaction ID must not be null");
        Objects.requireNonNull(amount, "Transaction amount must not be null");

        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Transaction amount must be representable in whole pence", exception);
        }

        if (amount.signum() == 0) {
            throw new IllegalArgumentException("Transaction amount must not be zero");
        }
    }
}
