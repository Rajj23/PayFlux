package com.payflux.notification_service.kafka;

import com.payflux.notification_service.entity.Notification;
import com.payflux.notification_service.entity.Transaction;
import com.payflux.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationConsumerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationConsumer notificationConsumer;

    @Test
    void testConsumerTransaction_SavesBothSenderAndReceiver() {
        Transaction transaction = new Transaction();
        transaction.setId(1L);
        transaction.setSenderId(10L);
        transaction.setReceiverId(20L);
        transaction.setAmount(500.0);

        when(notificationRepository.existsByTransactionIdAndUserId(1L, 20L)).thenReturn(false);
        when(notificationRepository.existsByTransactionIdAndUserId(1L, 10L)).thenReturn(false);

        notificationConsumer.consumerTransaction(transaction);

        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void testConsumerTransaction_SkipsDuplicateReceiverNotification() {
        Transaction transaction = new Transaction();
        transaction.setId(2L);
        transaction.setSenderId(10L);
        transaction.setReceiverId(20L);
        transaction.setAmount(300.0);

        // Receiver notification already exists
        when(notificationRepository.existsByTransactionIdAndUserId(2L, 20L)).thenReturn(true);
        when(notificationRepository.existsByTransactionIdAndUserId(2L, 10L)).thenReturn(false);

        notificationConsumer.consumerTransaction(transaction);

        // Only sender notification should be saved
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void testConsumerTransaction_SavesCorrectMessages() {
        Transaction transaction = new Transaction();
        transaction.setId(3L);
        transaction.setSenderId(5L);
        transaction.setReceiverId(7L);
        transaction.setAmount(100.0);

        when(notificationRepository.existsByTransactionIdAndUserId(anyLong(), anyLong())).thenReturn(false);

        notificationConsumer.consumerTransaction(transaction);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());

        boolean hasReceiverMsg = captor.getAllValues().stream()
                .anyMatch(n -> n.getMessage().contains("received"));
        boolean hasSenderMsg = captor.getAllValues().stream()
                .anyMatch(n -> n.getMessage().contains("sent"));

        assertTrue(hasReceiverMsg, "A 'received' message should be saved for receiver");
        assertTrue(hasSenderMsg, "A 'sent' message should be saved for sender");
    }
}
