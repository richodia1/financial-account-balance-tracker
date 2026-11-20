package com.example.financial_account_balance_tracker.exception;

/** A transaction violates its domain invariants. Messages never include input values. */
public final class InvalidTransactionException extends IllegalArgumentException {

    public InvalidTransactionException(String message) {
        super(message);
    }

    public InvalidTransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
