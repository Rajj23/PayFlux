package com.payflux.transaction_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.transaction_service.client.WalletRestClient;
import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.kafka.KafkaEventProducer;
import com.payflux.transaction_service.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
public class TransactionServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WalletRestClient walletRestClient;

    @MockBean
    private KafkaEventProducer kafkaEventProducer;

    @BeforeEach
    void setup() {
        transactionRepository.deleteAll();
    }

    @Test
    void testCreateTransaction_Integration() throws Exception {
        Transaction request = new Transaction();
        request.setSenderId(10L);
        request.setReceiverId(20L);
        request.setAmount(100.0);
        request.setIdempotencyKey("test-idem-key");

        when(walletRestClient.holdWallet(eq(10L), eq(100L), anyString())).thenReturn("hold-123");
        doNothing().when(walletRestClient).creditWallet(eq(20L), eq(100L), anyString());
        doNothing().when(walletRestClient).captureHold("hold-123");
        doNothing().when(kafkaEventProducer).sendTransactionEvent(anyString(), any(Transaction.class));

        mockMvc.perform(post("/api/transactions/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.senderId").value(10L));

        assertEquals(1, transactionRepository.findAll().size());
    }
}
