package com.example.financial_account_balance_tracker.api;

import java.math.BigDecimal;

/**
 * A decimal balance snapshot and its currency.
 */
public record BalanceResponse(BigDecimal balance, String currency) {
}
