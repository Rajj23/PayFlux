package com.payflux.user_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.user_service.client.WalletClient;
import com.payflux.user_service.dto.CreateWalletRequest;
import com.payflux.user_service.dto.LoginRequest;
import com.payflux.user_service.dto.SignupRequest;
import com.payflux.user_service.entity.User;
import com.payflux.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class UserServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WalletClient walletClient; // Mock Feign client to avoid real network calls

    @BeforeEach
    void setup() {
        userRepository.deleteAll();
    }

    @Test
    void testSignupAndLoginFlow() throws Exception {
        // Mock Feign client response (walletClient returns Object)
        Object mockResponse = new Object();
        org.mockito.Mockito.when(walletClient.createWallet(any(CreateWalletRequest.class))).thenReturn(mockResponse);

        // 1. Signup User
        SignupRequest signupRequest = new SignupRequest();
        signupRequest.setEmail("integration@test.com");
        signupRequest.setName("Integration User");
        signupRequest.setPassword("securepassword");

        mockMvc.perform(post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupRequest)))
                .andExpect(status().isOk());

        // Verify user is in DB
        User savedUser = userRepository.findByEmail("integration@test.com").orElseThrow();
        assert savedUser.getName().equals("Integration User");

        // 2. Login User
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("integration@test.com");
        loginRequest.setPassword("securepassword");

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists()); // Should return a JWT token
    }
}
