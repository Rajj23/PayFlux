package com.payflux.reward_service.service;

import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.repository.RewardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RewardServiceImplTest {

    @Mock
    private RewardRepository rewardRepository;

    @InjectMocks
    private RewardServiceImpl rewardService;

    @Test
    void testSendReward() {
        Reward request = new Reward();
        request.setUserId(1L);
        request.setPoints(500.0);

        Reward saved = new Reward();
        saved.setId(100L);
        saved.setUserId(1L);
        saved.setPoints(500.0);

        when(rewardRepository.save(any(Reward.class))).thenAnswer(invocation -> {
            Reward r = invocation.getArgument(0);
            assertNotNull(r.getSentAt()); // Ensure setSentAt was called
            saved.setSentAt(r.getSentAt());
            return saved;
        });

        Reward result = rewardService.sendReward(request);

        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertNotNull(result.getSentAt());
        verify(rewardRepository, times(1)).save(request);
    }

    @Test
    void testGetRewardsUserId() {
        Reward r1 = new Reward();
        r1.setId(1L);
        r1.setUserId(10L);

        when(rewardRepository.findByUserId(10L)).thenReturn(Arrays.asList(r1));

        List<Reward> results = rewardService.getRewardsUserId(10L);

        assertEquals(1, results.size());
        assertEquals(10L, results.get(0).getUserId());
        verify(rewardRepository, times(1)).findByUserId(10L);
    }

    @Test
    void testGetAllRewards() {
        Reward r1 = new Reward();
        r1.setId(1L);
        Reward r2 = new Reward();
        r2.setId(2L);

        when(rewardRepository.findAll()).thenReturn(Arrays.asList(r1, r2));

        List<Reward> results = rewardService.getAllRewards();

        assertEquals(2, results.size());
        verify(rewardRepository, times(1)).findAll();
    }
}
