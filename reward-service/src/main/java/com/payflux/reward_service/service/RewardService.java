package com.payflux.reward_service.service;

import com.payflux.reward_service.entity.Reward;

import java.util.List;

public interface RewardService {

    Reward sendReward(Reward reward);

    List<Reward> getRewardsUserId(Long userId);
    List<Reward> getAllRewards();
}
