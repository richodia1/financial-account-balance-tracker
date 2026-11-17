package com.example.financial_account_balance_tracker.producer;

import com.example.financial_account_balance_tracker.enums.TransactionType;
import com.example.financial_account_balance_tracker.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomTransactionFactoryTests {

    @ParameterizedTest
    @CsvSource({
            "CREDIT, false, 200.00",
            "CREDIT, true, 500000.00",
            "DEBIT, false, -200.00",
            "DEBIT, true, -500000.00"
    })
    void includesBothAmountBoundariesAndAppliesTheCorrectSign(
            TransactionType type, boolean upperBoundary, String expected) {
        UUID id = new UUID(0, 1);
        RandomTransactionFactory factory = new RandomTransactionFactory(
                (origin, bound) -> upperBoundary ? bound - 1 : origin, () -> id);

        Transaction transaction = factory.create(type);

        assertEquals(new BigDecimal(expected), transaction.amount());
        assertEquals(type, transaction.type());
        assertEquals(id, transaction.id());
    }

    @Test
    void generatesRandomIdsAndWholePenceAmountsWithinTheRequiredRange() {
        RandomTransactionFactory factory = new RandomTransactionFactory();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            TransactionType type = i % 2 == 0 ? TransactionType.CREDIT : TransactionType.DEBIT;
            Transaction transaction = factory.create(type);
            BigDecimal magnitude = transaction.amount().abs();

            assertTrue(ids.add(transaction.id()), "Generated IDs should be distinct");
            assertEquals(2, transaction.amount().scale());
            assertEquals(type, transaction.type());
            assertTrue(magnitude.compareTo(new BigDecimal("200.00")) >= 0);
            assertTrue(magnitude.compareTo(new BigDecimal("500000.00")) <= 0);
        }
    }

    @Test
    void rejectsAnUnspecifiedTransactionType() {
        assertThrows(NullPointerException.class, () -> new RandomTransactionFactory().create(null));
    }
}
