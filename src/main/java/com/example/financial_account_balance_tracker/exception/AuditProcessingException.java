package com.example.financial_account_balance_tracker.exception;

/** A completed audit submission failed during processing or console delivery. */
public final class AuditProcessingException extends IllegalStateException {

    public AuditProcessingException(String message) {
        super(message);
    }

    public AuditProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
