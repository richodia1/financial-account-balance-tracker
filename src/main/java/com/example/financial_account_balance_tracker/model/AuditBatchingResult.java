package com.example.financial_account_balance_tracker.model;

import java.util.List;

/** The packing and a lower bound, not an assertion that every packing is optimal. */
public record AuditBatchingResult(List<AuditBatch> batches, int batchCountLowerBound) {

    public AuditBatchingResult {
        batches = List.copyOf(batches);
        if (batches.isEmpty()) {
            throw new IllegalArgumentException("A packing must contain batches");
        }
        if (batchCountLowerBound < 1 || batchCountLowerBound > batches.size()) {
            throw new IllegalArgumentException("Batch count lower bound must be between one and the batch count");
        }
    }

    public boolean optimalityProven() {
        return batches.size() == batchCountLowerBound;
    }
}
