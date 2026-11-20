package com.example.financial_account_balance_tracker.exception;

/** Invalid internal audit data, including a batch that exceeds its capacity. */
public final class InvalidAuditSubmissionException extends IllegalArgumentException {

    public InvalidAuditSubmissionException(String message) {
        super(message);
    }

    public InvalidAuditSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
