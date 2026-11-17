package com.example.financial_account_balance_tracker.service;

import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;

@FunctionalInterface
public interface AuditBatchingService {

    AuditBatchingResult pack(AuditSubmission submission);
}
