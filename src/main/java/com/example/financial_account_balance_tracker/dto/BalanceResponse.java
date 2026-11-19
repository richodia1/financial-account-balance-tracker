package com.example.financial_account_balance_tracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * The HTTP response contract: a decimal balance snapshot and its currency.
 */
public record BalanceResponse(
        @Schema(description = "Exact decimal balance in pounds; may be negative.",
                example = "12345.67", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal balance,
        @Schema(description = "Account currency.", example = "GBP", allowableValues = "GBP",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String currency) {
}
