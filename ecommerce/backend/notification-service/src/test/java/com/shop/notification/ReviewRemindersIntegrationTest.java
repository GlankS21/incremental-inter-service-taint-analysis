package com.shop.notification;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.OrderDeliveredEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.common.event.ReviewReminderDueEvent;
import com.shop.common.event.ReviewReminderItem;
import com.shop.common.security.HmacJwtSupport;
import com.shop.common.security.JwtClaimNames;
import com.shop.common.security.JwtProperties;
import com.shop.notification.entity.Notification;
import com.shop.notification.entity.NotificationStatus;
import com.shop.notification.entity.NotificationType;
import com.shop.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review requests: «Оцените покупку» in the account on {@code order.delivered}, one reminder e-mail on
 * {@code review.reminder-due} unless turned off in the caller's own settings.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + ReviewRemindersIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.notification.shop-name=TechShop",
        "app.notification.frontend-url=https://shop.example.com/",
        "app.notification.retry.interval=1h"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReviewRemindersIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIz";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void deliveredOrderAddsOneInAppRequestAndNoEmail() throws Exception {
        UUID buyer = UUID.randomUUID();
        OrderDeliveredEvent event = delivered(buyer, "ORD-20261029-AAAAAA", "anna@example.ru");

        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_DELIVERED_KEY, event);
        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_DELIVERED_KEY, event);

        Notification request = await(() -> notificationRepository.findAll().stream()
                .filter(n -> buyer.equals(n.getUserId()) && n.getType() == NotificationType.REVIEW_REQUEST).findFirst());
        assertThat(request.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(request.getSentAt()).isNotNull();
        assertThat(request.getSubject()).isEqualTo("Оцените покупку");
        assertThat(request.getBody())
                .startsWith("Заказ ORD-20261029-AAAAAA доставлен")
                .contains("о 2 товарах", "  - Смартфон", "  - Чехол")
                .contains("Оставить отзыв: https://shop.example.com/account/reviews");
        Thread.sleep(1_000);
        assertThat(notificationRepository.findAll()).filteredOn(n -> buyer.equals(n.getUserId())).hasSize(1);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, token(buyer, "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].type").value("REVIEW_REQUEST"))
                .andExpect(jsonPath("$.data.content[0].channel").value("IN_APP"));
        // Another customer does not see it
        mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION,
                        token(UUID.randomUUID(), "ROLE_CUSTOMER")))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        // Nothing to e-mail again
        mockMvc.perform(post("/api/notifications/admin/" + request.getId() + "/resend")
                        .header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), "ROLE_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Уведомление в личном кабинете не отправляется по почте, его нельзя отправить повторно"));
    }

    @Test
    void reminderIsEmailedOnceToTheAddressOfTheOrder() throws Exception {
        UUID buyer = UUID.randomUUID();
        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_DELIVERED_KEY,
                delivered(buyer, "ORD-20261029-BBBBBB", "boris@example.ru"));
        await(() -> notificationRepository.findAll().stream()
                .filter(n -> buyer.equals(n.getUserId())).findFirst());

        ReviewReminderDueEvent reminder = reminder(buyer, "ORD-20261029-BBBBBB");
        rabbitTemplate.convertAndSend(MessagingConstants.PRODUCT_EXCHANGE, MessagingConstants.REVIEW_REMINDER_DUE_KEY,
                reminder);
        rabbitTemplate.convertAndSend(MessagingConstants.PRODUCT_EXCHANGE, MessagingConstants.REVIEW_REMINDER_DUE_KEY,
                reminder);

        Notification sent = await(() -> notificationRepository.findAll().stream()
                .filter(n -> buyer.equals(n.getUserId()) && n.getType() == NotificationType.REVIEW_REMINDER
                        && n.getStatus() == NotificationStatus.SENT)
                .findFirst());
        assertThat(sent.getRecipient()).isEqualTo("boris@example.ru");
        assertThat(sent.getSubject()).isEqualTo("[TechShop] Оцените покупку");
        assertThat(sent.getReference()).isEqualTo("ORD-20261029-BBBBBB");
        assertThat(sent.getBody())
                .contains("Недавно мы доставили ваш заказ ORD-20261029-BBBBBB. Вы ещё не оценили 2 товара:")
                .contains("  - Смартфон\n    https://shop.example.com/account/reviews?product=5")
                .contains("  - Чехол\n    https://shop.example.com/account/reviews?product=6")
                .contains("https://shop.example.com/account/notifications");
        Thread.sleep(1_000);
        assertThat(notificationRepository.findAll())
                .filteredOn(n -> buyer.equals(n.getUserId()) && n.getType() == NotificationType.REVIEW_REMINDER)
                .hasSize(1);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("boris@example.ru");
    }

    @Test
    void customersTurnRemindersOffInTheirOwnSettingsOnly() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String buyerToken = token(buyer, "ROLE_CUSTOMER");

        mockMvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/preferences").header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewReminders").value(true));
        mockMvc.perform(put("/api/notifications/preferences").header(HttpHeaders.AUTHORIZATION, buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/notifications/preferences").header(HttpHeaders.AUTHORIZATION, buyerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewReminders\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Настройки уведомлений сохранены"))
                .andExpect(jsonPath("$.data.reviewReminders").value(false));
        mockMvc.perform(get("/api/notifications/preferences").header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(jsonPath("$.data.reviewReminders").value(false));
        // Settings are per account: the other customer keeps the default
        mockMvc.perform(get("/api/notifications/preferences").header(HttpHeaders.AUTHORIZATION,
                        token(other, "ROLE_CUSTOMER")))
                .andExpect(jsonPath("$.data.reviewReminders").value(true));

        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_DELIVERED_KEY,
                delivered(buyer, "ORD-20261029-CCCCCC", "vera@example.ru"));
        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_DELIVERED_KEY,
                delivered(other, "ORD-20261029-DDDDDD", "gleb@example.ru"));
        await(() -> notificationRepository.findAll().stream()
                .filter(n -> buyer.equals(n.getUserId())).findFirst());
        await(() -> notificationRepository.findAll().stream()
                .filter(n -> other.equals(n.getUserId())).findFirst());

        rabbitTemplate.convertAndSend(MessagingConstants.PRODUCT_EXCHANGE, MessagingConstants.REVIEW_REMINDER_DUE_KEY,
                reminder(buyer, "ORD-20261029-CCCCCC"));
        rabbitTemplate.convertAndSend(MessagingConstants.PRODUCT_EXCHANGE, MessagingConstants.REVIEW_REMINDER_DUE_KEY,
                reminder(other, "ORD-20261029-DDDDDD"));

        Notification othersReminder = await(() -> notificationRepository.findAll().stream()
                .filter(n -> other.equals(n.getUserId()) && n.getType() == NotificationType.REVIEW_REMINDER)
                .findFirst());
        assertThat(othersReminder.getRecipient()).isEqualTo("gleb@example.ru");
        Thread.sleep(1_000);
        assertThat(notificationRepository.findAll())
                .filteredOn(n -> buyer.equals(n.getUserId()) && n.getType() == NotificationType.REVIEW_REMINDER)
                .isEmpty();
    }

    private static OrderDeliveredEvent delivered(UUID userId, String orderNumber, String email) {
        List<OrderItemPayload> items = List.of(
                new OrderItemPayload(5L, "SKU-5", "Смартфон", 1, new BigDecimal("50000")),
                new OrderItemPayload(6L, "SKU-6", "Чехол", 2, new BigDecimal("1000")));
        return new OrderDeliveredEvent(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), orderNumber, userId,
                email, items);
    }

    private static ReviewReminderDueEvent reminder(UUID userId, String orderNumber) {
        return new ReviewReminderDueEvent(UUID.randomUUID(), Instant.now(), userId, UUID.randomUUID(), orderNumber,
                List.of(new ReviewReminderItem(5L, "Смартфон", "smartfon"),
                        new ReviewReminderItem(6L, "Чехол", "chekhol")));
    }

    private static <T> T await(Supplier<Optional<T>> probe) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Optional<T> value;
        while ((value = probe.get()).isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within 15 seconds");
            }
            Thread.sleep(200);
        }
        return value.get();
    }

    private String token(UUID subject, String role) {
        JwtProperties properties = new JwtProperties(SECRET, "test-issuer");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(HmacJwtSupport.secretKey(properties)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(JwtClaimNames.EMAIL, "someone@example.ru")
                .claim(JwtClaimNames.ROLES, List.of(role))
                .claim(JwtClaimNames.TOKEN_TYPE, JwtClaimNames.ACCESS_TOKEN_TYPE)
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(HmacJwtSupport.ALGORITHM).build(), claims)).getTokenValue();
    }
}
