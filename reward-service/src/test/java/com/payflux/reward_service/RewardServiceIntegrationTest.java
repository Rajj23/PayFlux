package com.payflux.reward_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.repository.RewardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test") // Ensures it uses application-test.yml if present, or we can just rely on H2
                        // default config
public class RewardServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RewardRepository rewardRepository;

    @BeforeEach
    void setup() {
        rewardRepository.deleteAll();
    }

    @Test
    void testGetAllRewardsIntegration() throws Exception {
        Reward r1 = new Reward();
        r1.setUserId(10L);
        r1.setPoints(100.0);
        r1.setTransactionId(1L);
        r1.setSentAt(LocalDateTime.now());
        rewardRepository.save(r1);

        Reward r2 = new Reward();
        r2.setUserId(20L);
        r2.setPoints(200.0);
        r2.setTransactionId(2L);
        r2.setSentAt(LocalDateTime.now());
        rewardRepository.save(r2);

        mockMvc.perform(get("/api/rewards")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].userId").value(10L))
                .andExpect(jsonPath("$[1].userId").value(20L));
    }

    @Test
    void testGetRewardsByUserIdIntegration() throws Exception {
        Reward r1 = new Reward();
        r1.setUserId(15L);
        r1.setPoints(300.0);
        r1.setTransactionId(3L);
        r1.setSentAt(LocalDateTime.now());
        rewardRepository.save(r1);

        mockMvc.perform(get("/api/rewards/user/15")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].points").value(300.0));
    }
}
