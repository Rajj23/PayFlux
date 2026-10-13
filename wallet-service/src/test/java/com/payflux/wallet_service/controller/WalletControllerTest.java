package com.payflux.wallet_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.wallet_service.dto.*;
import com.payflux.wallet_service.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
@AutoConfigureMockMvc(addFilters = false)
public class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WalletService walletService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testCreateWallet() throws Exception {
        CreateWalletRequest request = new CreateWalletRequest();
        request.setUserId(1L);
        request.setCurrency("USD");

        WalletResponse response = new WalletResponse(100L, 1L, "USD", 0L, 0L);

        when(walletService.createWallet(any(CreateWalletRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.userId").value(1L));
    }

    @Test
    void testGetWallet() throws Exception {
        WalletResponse response = new WalletResponse(100L, 1L, "USD", 50L, 50L);

        when(walletService.getWallet(eq(1L))).thenReturn(response);

        mockMvc.perform(get("/api/v1/wallets/1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50L))
                .andExpect(jsonPath("$.userId").value(1L));
    }

    @Test
    void testCreditWallet() throws Exception {
        CreditRequest request = new CreditRequest();
        request.setUserId(1L);
        request.setAmount(100L);
        request.setCurrency("USD");

        WalletResponse response = new WalletResponse(100L, 1L, "USD", 150L, 150L);

        when(walletService.credit(any(CreditRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/wallets/credit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150L));
    }
}
