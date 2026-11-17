package com.example.financial_account_balance_tracker.service;

import com.example.financial_account_balance_tracker.model.Transaction;

import java.math.BigDecimal;

/**
 * Aggregates transactions and tracks the balance for a single account.
 */
public interface BankAccountService {

    /**
     * Applies a transaction to the balance. Safe to call from multiple threads.
     *
     * @param transaction transaction to process
     */
    void processTransaction(Transaction transaction);

    /**
     * Returns the balance through the required floating-point interface.
     * Use {@link #retrieveExactBalance()} when decimal precision must be retained.
     */
    double retrieveBalance();

    /**
     * Returns an immutable, exact GBP balance snapshot with two decimal places.
     */
    BigDecimal retrieveExactBalance();
}
