package com.example.financial_account_balance_tracker.model;

import com.example.financial_account_balance_tracker.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionTests {

    private static final UUID ID = new UUID(0, 1);

    @ParameterizedTest
    @CsvSource({
            "200, 200.00, CREDIT",
            "200.1, 200.10, CREDIT",
            "-500000, -500000.00, DEBIT",
            "0.01, 0.01, CREDIT",
            "-0.01, -0.01, DEBIT",
            "200.000, 200.00, CREDIT"
    })
    void preservesSignedValueAndIdentifiesTransactionType(
            String amount, String expected, TransactionType expectedType) {
        Transaction transaction = new Transaction(ID, new BigDecimal(amount));

        assertEquals(new BigDecimal(expected), transaction.amount());
        assertEquals(expectedType, transaction.type());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.00"})
    void rejectsZeroAmounts(String amount) {
        assertThrows(IllegalArgumentException.class,
                () -> new Transaction(ID, new BigDecimal(amount)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"200.001", "-200.001", "0.001"})
    void rejectsFractionalPenceInsteadOfRounding(String amount) {
        assertThrows(IllegalArgumentException.class,
                () -> new Transaction(ID, new BigDecimal(amount)));
    }

    @Test
    void rejectsMissingId() {
        assertThrows(NullPointerException.class,
                () -> new Transaction(null, new BigDecimal("200.00")));
    }

    @Test
    void rejectsMissingAmount() {
        assertThrows(NullPointerException.class, () -> new Transaction(ID, null));
    }
}
