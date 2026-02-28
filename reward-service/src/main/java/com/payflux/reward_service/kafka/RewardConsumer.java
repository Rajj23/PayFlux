package com.payflux.reward_service.kafka;

import java.time.LocalDateTime;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.entity.Transaction;
import com.payflux.reward_service.repository.RewardRepository;

@Component
public class RewardConsumer {

    private final RewardRepository rewardRepository;

    public RewardConsumer(RewardRepository rewardRepository) {
        this.rewardRepository = rewardRepository;
    }


    @KafkaListener(topics = "txn-initiated", groupId = "reward-group")
    public void consumerTransaction(Transaction transaction) {
        try{
            if(rewardRepository.existsByTransactionId(transaction.getId())){
                System.out.printf("Reward already exists for transaction: "+ transaction.getId());
                return;
            }
            Reward reward = new Reward();
            reward.setUserId(transaction.getSenderId());
            reward.setPoints(transaction.getAmount() * 100);
            reward.setSentAt(LocalDateTime.now());
            reward.setTransactionId(transaction.getId());

            rewardRepository.save(reward);

            System.out.println("Reward saved: "+reward);
        }
        catch (Exception e){
            System.err.println("Failed to process transaction "+transaction.getId() +": "+e.getMessage());
            throw e;
        }
    }
}
