package com.payflux.notification_service.kafka;

import java.time.LocalDateTime;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.payflux.notification_service.entity.Notification;
import com.payflux.notification_service.entity.Transaction;
import com.payflux.notification_service.repository.NotificationRepository;

@Component
public class NotificationConsumer {
    private final NotificationRepository notificationRepository;

    public NotificationConsumer(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @KafkaListener(topics = "txn-initiated", groupId = "notification-group")
    public void consumerTransaction(Transaction transaction) {

        // Notify receiver
        Notification receiverNotification = new Notification();
        receiverNotification.setUserId(transaction.getReceiverId());
        receiverNotification.setMessage("₹" + transaction.getAmount() + " received from user " + transaction.getSenderId());
        receiverNotification.setSentAt(LocalDateTime.now());
        notificationRepository.save(receiverNotification);
        System.out.println("Receiver notification saved:" + receiverNotification);

        // Notify sender
        Notification senderNotification = new Notification();
        senderNotification.setUserId(transaction.getSenderId());
        senderNotification.setMessage("₹" + transaction.getAmount() + " sent to user " + transaction.getReceiverId());
        senderNotification.setSentAt(LocalDateTime.now());
        notificationRepository.save(senderNotification);
        System.out.println("Sender notification saved:" + senderNotification);
    }

}
