package com.finsecure.finsecure_pay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ValidationService {
    private static final Pattern ACCOUNT = Pattern.compile("\\d{10}");
    private final BigDecimal maxAmount;
    private final Set<String> allowedCurrencies;

    public ValidationService(@Value("${MAX_TXN_AMOUNT:10000}") BigDecimal maxAmount,
                             @Value("${ALLOWED_CURRENCIES:SGD,USD,EUR}") String currencies) {
        this.maxAmount = maxAmount;
        this.allowedCurrencies = Arrays.stream(currencies.split(","))
                .map(String::trim).collect(Collectors.toSet());
    }

    public ValidationResult validate(TransactionRequest r) {
        List<String> errors = new ArrayList<>();
        if (r == null) return new ValidationResult(false, List.of("Request body is required"));
        if (r.transactionId() == null || r.transactionId().isBlank())
            errors.add("transactionId is required");
        if (r.senderAccount() == null || !ACCOUNT.matcher(r.senderAccount()).matches())
            errors.add("senderAccount must be 10 digits");
        if (r.receiverAccount() == null || !ACCOUNT.matcher(r.receiverAccount()).matches())
            errors.add("receiverAccount must be 10 digits");
        if (r.senderAccount() != null && r.senderAccount().equals(r.receiverAccount()))
            errors.add("sender and receiver must be different");
        if (r.amount() == null || r.amount().signum() <= 0)
            errors.add("amount must be greater than 0");
        else if (r.amount().compareTo(maxAmount) > 0)
            errors.add("amount exceeds limit of " + maxAmount);
        if (r.currency() == null || !allowedCurrencies.contains(r.currency()))
            errors.add("currency must be one of " + allowedCurrencies);
        return new ValidationResult(errors.isEmpty(), errors);
    }
}