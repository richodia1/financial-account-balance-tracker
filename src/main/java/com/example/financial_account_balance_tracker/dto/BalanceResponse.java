package com.example.financial_account_balance_tracker.dto;

import java.math.BigDecimal;

/**
 * The HTTP response contract: a decimal balance snapshot and its currency.
 */
public record BalanceResponse(BigDecimal balance, String currency) {
}
