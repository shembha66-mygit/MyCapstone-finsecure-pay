package com.finsecure.finsecure_pay;

import java.util.List;

public record ValidationResult(boolean valid, List<String> errors) {}