package com.example.financial_account_balance_tracker.dto;

import java.math.BigDecimal;

public record AuditBatchSummary(BigDecimal totalValueOfAllTransactions, int countOfTransactions) {
}
