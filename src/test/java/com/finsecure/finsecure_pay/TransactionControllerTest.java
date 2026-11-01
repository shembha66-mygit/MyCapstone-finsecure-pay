package com.finsecure.finsecure_pay;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransactionControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ValidationService service = new ValidationService(new BigDecimal("10000"), "SGD,USD,EUR");
        TransactionController controller = new TransactionController(service, new SimpleMeterRegistry());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void healthReturnsUp() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test void validTransactionReturns200() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("""
            {"transactionId":"T1","senderAccount":"1234567890","receiverAccount":"0987654321",
             "amount":100,"currency":"SGD"}"""))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.valid").value(true));
    }

    @Test void invalidTransactionReturns400() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("""
            {"transactionId":"T2","senderAccount":"1234567890","receiverAccount":"0987654321",
             "amount":-1,"currency":"SGD"}"""))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.valid").value(false));
    }
}