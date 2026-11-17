package com.example.financial_account_balance_tracker.service.impl;

import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.BankAccountService;
import com.example.financial_account_balance_tracker.service.TransactionAuditService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single-account, in-memory balance tracker with exact decimal arithmetic.
 * Balance reads and updates use the same monitor for visibility and atomicity.
 */
@Service
public final class InMemoryBankAccountService implements BankAccountService {

    private final TransactionAuditService auditService;
    private BigDecimal balance = new BigDecimal("0.00");

    public InMemoryBankAccountService(TransactionAuditService auditService) {
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public synchronized void processTransaction(Transaction transaction) {
        Objects.requireNonNull(transaction, "Transaction must not be null");
        BigDecimal nextBalance = balance.add(transaction.amount());
        auditService.record(transaction);
        balance = nextBalance;
    }

    @Override
    public double retrieveBalance() {
        return retrieveExactBalance().doubleValue();
    }

    @Override
    public synchronized BigDecimal retrieveExactBalance() {
        return balance;
    }
}
