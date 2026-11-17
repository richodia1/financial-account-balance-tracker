package com.example.financial_account_balance_tracker.producer;

import com.example.financial_account_balance_tracker.enums.TransactionType;
import com.example.financial_account_balance_tracker.model.Transaction;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongBinaryOperator;
import java.util.function.Supplier;

/**
 * Generates signed transactions using an inclusive range of whole-pence values.
 */
public final class RandomTransactionFactory {

    private static final long MIN_PENCE = 20_000;
    private static final long MAX_PENCE = 50_000_000;

    private final LongBinaryOperator randomPence;
    private final Supplier<UUID> randomId;

    public RandomTransactionFactory() {
        this((origin, bound) -> ThreadLocalRandom.current().nextLong(origin, bound), UUID::randomUUID);
    }

    RandomTransactionFactory(LongBinaryOperator randomPence, Supplier<UUID> randomId) {
        this.randomPence = Objects.requireNonNull(randomPence);
        this.randomId = Objects.requireNonNull(randomId);
    }

    public Transaction create(TransactionType type) {
        Objects.requireNonNull(type, "Transaction type must not be null");
        long pence = randomPence.applyAsLong(MIN_PENCE, MAX_PENCE + 1);
        BigDecimal magnitude = BigDecimal.valueOf(pence, 2);
        BigDecimal amount = type == TransactionType.CREDIT ? magnitude : magnitude.negate();
        return new Transaction(randomId.get(), amount);
    }
}
