package com.example.financial_account_balance_tracker.model;

import com.example.financial_account_balance_tracker.exception.InvalidAuditSubmissionException;

import java.util.List;

/** Immutable batch assignments and a certified lower bound on their count. */
public record AuditBatchingResult(List<AuditBatch> batches, int batchCountLowerBound) {

    public AuditBatchingResult {
        try {
            batches = List.copyOf(batches);
        } catch (NullPointerException exception) {
            throw new InvalidAuditSubmissionException("Audit batches must be present and must not contain nulls", exception);
        }
        if (batches.isEmpty()) {
            throw new InvalidAuditSubmissionException("A packing must contain batches");
        }
        if (batchCountLowerBound < 1 || batchCountLowerBound > batches.size()) {
            throw new InvalidAuditSubmissionException("Batch count lower bound must be between one and the batch count");
        }
    }

    public boolean optimalityProven() {
        return batches.size() == batchCountLowerBound;
    }
}
