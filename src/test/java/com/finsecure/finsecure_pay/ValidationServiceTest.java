package com.finsecure.finsecure_pay;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationServiceTest {
    private final ValidationService svc = new ValidationService(new BigDecimal("10000"), "SGD,USD,EUR");

    private TransactionRequest req(String amt, String cur, String from, String to) {
        return new TransactionRequest("T1", from, to, new BigDecimal(amt), cur);
    }

    @Test void validTransactionPasses() {
        assertTrue(svc.validate(req("100.50", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void zeroAmountFails() {
        assertFalse(svc.validate(req("0", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void negativeAmountFails() {
        assertFalse(svc.validate(req("-5", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void overLimitFails() {
        assertFalse(svc.validate(req("10000.01", "SGD", "1234567890", "0987654321")).valid());
    }
    @Test void unsupportedCurrencyFails() {
        assertFalse(svc.validate(req("100", "XYZ", "1234567890", "0987654321")).valid());
    }
    @Test void sameAccountFails() {
        assertFalse(svc.validate(req("100", "SGD", "1234567890", "1234567890")).valid());
    }
    @Test void badAccountFormatFails() {
        assertFalse(svc.validate(req("100", "SGD", "123", "0987654321")).valid());
    }
}