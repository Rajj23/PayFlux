package com.payflux.transaction_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.transaction_service.entity.Transaction;
import com.payflux.transaction_service.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false) // Ignore security filters
public class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testCreateTransaction() throws Exception {
        Transaction input = new Transaction();
        input.setSenderId(1L);
        input.setReceiverId(2L);
        input.setAmount(100.0);

        Transaction output = new Transaction();
        output.setId(10L);
        output.setSenderId(1L);
        output.setReceiverId(2L);
        output.setAmount(100.0);
        output.setStatus("SUCCESS");

        when(transactionService.createTransaction(any(Transaction.class))).thenReturn(output);

        mockMvc.perform(post("/api/transactions/create")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "1")
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void createTransactionRequiresAuthenticatedIdentity() throws Exception {
        Transaction input = new Transaction();
        input.setSenderId(1L);
        input.setReceiverId(2L);
        input.setAmount(100.0);

        mockMvc.perform(post("/api/transactions/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testGetAllTransactions() throws Exception {
        Transaction t1 = new Transaction();
        t1.setId(1L);
        t1.setAmount(50.0);

        Transaction t2 = new Transaction();
        t2.setId(2L);
        t2.setAmount(75.0);

        when(transactionService.getAllTransactions()).thenReturn(Arrays.asList(t1, t2));

        mockMvc.perform(get("/api/transactions/all")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[1].id").value(2L));
    }
}
