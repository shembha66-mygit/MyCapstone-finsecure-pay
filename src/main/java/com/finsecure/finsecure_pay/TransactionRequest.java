package com.finsecure.finsecure_pay;

import java.math.BigDecimal;

public record TransactionRequest(String transactionId, String senderAccount,
        String receiverAccount, BigDecimal amount, String currency) {}