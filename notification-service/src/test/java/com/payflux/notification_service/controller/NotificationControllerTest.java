package com.payflux.notification_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflux.notification_service.entity.Notification;
import com.payflux.notification_service.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
public class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testSendNotification() throws Exception {
        Notification input = new Notification();
        input.setUserId(1L);
        input.setTransactionId(100L);
        input.setMessage("Payment received");

        Notification saved = new Notification();
        saved.setId(10L);
        saved.setUserId(1L);
        saved.setTransactionId(100L);
        saved.setMessage("Payment received");
        saved.setSentAt(LocalDateTime.now());

        when(notificationService.sendNotification(any(Notification.class))).thenReturn(saved);

        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.userId").value(1L));
    }

    @Test
    void testGetNotificationsByUser() throws Exception {
        Notification n1 = new Notification();
        n1.setUserId(5L);
        n1.setMessage("TX 1");

        when(notificationService.getNotificationByUserId(eq(5L))).thenReturn(Arrays.asList(n1));

        mockMvc.perform(get("/api/notifications/5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].userId").value(5L));
    }
}
