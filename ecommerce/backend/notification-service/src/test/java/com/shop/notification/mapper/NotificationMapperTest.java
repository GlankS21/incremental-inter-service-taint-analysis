package com.shop.notification.mapper;

import com.shop.notification.dto.response.NotificationResponse;
import com.shop.notification.entity.Notification;
import com.shop.notification.entity.NotificationStatus;
import com.shop.notification.entity.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMapperTest {

    private final NotificationMapper mapper = new NotificationMapperImpl();

    @Test
    void hidesTheBodyOfPasswordResetEmails() {
        Notification notification = notification(NotificationType.PASSWORD_RESET,
                "Link: http://localhost:3000/reset-password?token=secret");

        NotificationResponse response = mapper.toResponse(notification);

        assertThat(response.body()).isNull();
        assertThat(response.subject()).isEqualTo("Subject");
        assertThat(response.type()).isEqualTo(NotificationType.PASSWORD_RESET);
        assertThat(response.recipient()).isEqualTo("ivan.petrov@example.ru");
        // The stored e-mail is untouched, so that it can be resent
        assertThat(notification.getBody()).contains("token=secret");
    }

    @Test
    void returnsTheBodyOfOtherEmails() {
        NotificationResponse response = mapper.toResponse(notification(NotificationType.WELCOME, "Welcome"));

        assertThat(response.body()).isEqualTo("Welcome");
        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);
    }

    private Notification notification(NotificationType type, String body) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setEventId(UUID.randomUUID());
        notification.setType(type);
        notification.setUserId(UUID.randomUUID());
        notification.setRecipient("ivan.petrov@example.ru");
        notification.setSubject("Subject");
        notification.setBody(body);
        notification.setStatus(NotificationStatus.SENT);
        return notification;
    }
}
