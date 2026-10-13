package com.payflux.reward_service.controller;

import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.service.RewardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RewardController.class)
public class RewardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RewardService rewardService;

    @Test
    void testGetAllRewards() throws Exception {
        Reward r1 = new Reward();
        r1.setId(1L);
        r1.setUserId(10L);
        r1.setPoints(100.0);

        Reward r2 = new Reward();
        r2.setId(2L);
        r2.setUserId(20L);
        r2.setPoints(200.0);

        List<Reward> rewards = Arrays.asList(r1, r2);

        when(rewardService.getAllRewards()).thenReturn(rewards);

        mockMvc.perform(get("/api/rewards")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].userId").value(10L))
                .andExpect(jsonPath("$[1].userId").value(20L));
    }

    @Test
    void testGetRewardsByUserId() throws Exception {
        Reward r1 = new Reward();
        r1.setId(1L);
        r1.setUserId(10L);
        r1.setPoints(100.0);

        List<Reward> rewards = Arrays.asList(r1);

        when(rewardService.getRewardsUserId(10L)).thenReturn(rewards);

        mockMvc.perform(get("/api/rewards/user/10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].userId").value(10L));
    }
}
