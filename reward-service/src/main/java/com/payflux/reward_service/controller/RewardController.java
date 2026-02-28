package com.payflux.reward_service.controller;

import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.service.RewardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rewards")
public class RewardController {
    private final RewardService rewardService;

    public RewardController(RewardService rewardService) {
        this.rewardService = rewardService;
    }

    @GetMapping
    public List<Reward> getAllRewards(){
        return rewardService.getAllRewards();
    }

    @GetMapping("/user/{userId}")
    public List<Reward> getRewardsByUserId(@PathVariable Long userId){
        return rewardService.getRewardsUserId(userId);
    }
}
