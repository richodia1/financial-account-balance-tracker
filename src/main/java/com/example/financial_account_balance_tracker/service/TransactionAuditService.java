package com.example.financial_account_balance_tracker.service;

import com.example.financial_account_balance_tracker.model.Transaction;

/**
 * Records a transaction, handing off each completed audit window atomically.
 */
@FunctionalInterface
public interface TransactionAuditService {

    void record(Transaction transaction);
}
