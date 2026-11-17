package com.example.financial_account_balance_tracker.dto;

import java.util.List;

public record AuditSubmissionSummary(List<AuditBatchSummary> batches) {

    public AuditSubmissionSummary {
        batches = List.copyOf(batches);
    }
}
