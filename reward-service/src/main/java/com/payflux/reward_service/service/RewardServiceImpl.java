package com.payflux.reward_service.service;

import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.repository.RewardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RewardServiceImpl implements RewardService{

    @Autowired
    private RewardRepository rewardRepository;

    @Override
    public Reward sendReward(Reward reward) {
        reward.setSentAt(LocalDateTime.now());
        return rewardRepository.save(reward);
    }

    @Override
    public List<Reward> getRewardsUserId(Long userId) {
        return rewardRepository.findByUserId(userId);
    }

    @Override
    public List<Reward> getAllRewards() {
        return rewardRepository.findAll();
    }
}
