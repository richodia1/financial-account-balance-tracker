package com.example.financial_account_balance_tracker.audit;

import com.example.financial_account_balance_tracker.model.AuditSubmission;

/**
 * Processes a complete submission outside transaction and balance locks.
 */
@FunctionalInterface
public interface AuditSubmissionHandler {

    void handle(AuditSubmission submission);
}
