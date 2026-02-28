package com.payflux.notification_service.service;

import com.payflux.notification_service.entity.Notification;

import java.util.List;

public interface NotificationService {
    Notification sendNotification(Notification notification);

    List<Notification> getNotificationByUserId(Long userId);
}
