package com.payflux.wallet_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.wallet_service.dto.CreateWalletRequest;
import com.payflux.wallet_service.dto.CreditRequest;
import com.payflux.wallet_service.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
public class WalletServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        walletRepository.deleteAll();
    }

    @Test
    void testCreateWalletFlow() throws Exception {
        CreateWalletRequest request = new CreateWalletRequest();
        request.setUserId(100L);
        request.setCurrency("INR");

        mockMvc.perform(post("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(100L))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value(0));

        assertEquals(1, walletRepository.findAll().size());
    }

    @Test
    void testGetWallet_AfterCreate() throws Exception {
        // First create
        CreateWalletRequest createRequest = new CreateWalletRequest();
        createRequest.setUserId(200L);
        createRequest.setCurrency("INR");

        mockMvc.perform(post("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk());

        // Then fetch
        mockMvc.perform(get("/api/v1/wallets/200")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(200L));
    }
}
