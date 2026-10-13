package com.payflux.notification_service.service;

import com.payflux.notification_service.entity.Notification;
import com.payflux.notification_service.repository.NotificationRepository;
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
public class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Test
    void testSendNotification_SavesAndReturnsSentAt() {
        Notification notification = new Notification();
        notification.setUserId(1L);
        notification.setTransactionId(100L);
        notification.setMessage("Payment received");

        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = notificationService.sendNotification(notification);

        assertNotNull(result.getSentAt(), "sentAt should be set automatically");
        assertEquals(1L, result.getUserId());
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void testGetNotificationByUserId() {
        Notification n1 = new Notification();
        n1.setUserId(5L);
        n1.setMessage("TX 1");

        Notification n2 = new Notification();
        n2.setUserId(5L);
        n2.setMessage("TX 2");

        when(notificationRepository.findByUserId(5L)).thenReturn(Arrays.asList(n1, n2));

        List<Notification> results = notificationService.getNotificationByUserId(5L);

        assertEquals(2, results.size());
        verify(notificationRepository, times(1)).findByUserId(5L);
    }
}
