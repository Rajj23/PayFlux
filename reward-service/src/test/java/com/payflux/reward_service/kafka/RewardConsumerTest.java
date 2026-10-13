package com.payflux.reward_service.kafka;

import com.payflux.reward_service.entity.Reward;
import com.payflux.reward_service.entity.Transaction;
import com.payflux.reward_service.repository.RewardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RewardConsumerTest {

    @Mock
    private RewardRepository rewardRepository;

    @InjectMocks
    private RewardConsumer rewardConsumer;

    @Test
    void testConsumerTransaction_NewReward() {
        Transaction transaction = new Transaction();
        transaction.setId(100L);
        transaction.setSenderId(10L);
        transaction.setAmount(50.0);

        when(rewardRepository.existsByTransactionId(100L)).thenReturn(false);

        rewardConsumer.consumerTransaction(transaction);

        ArgumentCaptor<Reward> rewardCaptor = ArgumentCaptor.forClass(Reward.class);
        verify(rewardRepository, times(1)).save(rewardCaptor.capture());

        Reward savedReward = rewardCaptor.getValue();
        assertEquals(10L, savedReward.getUserId());
        assertEquals(5000.0, savedReward.getPoints()); // 50.0 * 100
        assertEquals(100L, savedReward.getTransactionId());
        assertNotNull(savedReward.getSentAt());
    }

    @Test
    void testConsumerTransaction_RewardAlreadyExists() {
        Transaction transaction = new Transaction();
        transaction.setId(100L);

        when(rewardRepository.existsByTransactionId(100L)).thenReturn(true);

        rewardConsumer.consumerTransaction(transaction);

        verify(rewardRepository, never()).save(any(Reward.class));
    }
}
