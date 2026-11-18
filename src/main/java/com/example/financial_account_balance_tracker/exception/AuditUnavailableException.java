package com.example.financial_account_balance_tracker.exception;

import java.util.concurrent.RejectedExecutionException;

/** The audit hand-off cannot accept work; the completing transaction is not committed. */
public final class AuditUnavailableException extends RejectedExecutionException {

    public AuditUnavailableException(String message) {
        super(message);
    }

    public AuditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
