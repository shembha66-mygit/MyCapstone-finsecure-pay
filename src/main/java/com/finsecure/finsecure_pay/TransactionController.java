package com.finsecure.finsecure_pay;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class TransactionController {
    private final ValidationService service;
    private final MeterRegistry registry;

    public TransactionController(ValidationService service, MeterRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@RequestBody TransactionRequest request) {
        ValidationResult result = service.validate(request);
        registry.counter("finsecure_validation_total", "result", result.valid() ? "pass" : "fail").increment();
        return result.valid() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }
}