package com.shop.payment;

import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.PaymentRequestedEvent;
import com.shop.common.event.PaymentSucceededEvent;
import com.shop.payment.entity.NotificationStatus;
import com.shop.payment.entity.Payment;
import com.shop.payment.entity.PaymentNotification;
import com.shop.payment.entity.PaymentProvider;
import com.shop.payment.entity.PaymentStatus;
import com.shop.payment.gateway.GatewayException;
import com.shop.payment.gateway.PaymentGateway;
import com.shop.payment.gateway.PaymentGateway.GatewayPayment;
import com.shop.payment.gateway.PaymentGateway.Status;
import com.shop.payment.repository.PaymentNotificationRepository;
import com.shop.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook of YooKassa ({@code PAYMENT_PROVIDER=yookassa}) with the provider's API replaced by a mock:
 * the endpoint needs no token, and a notification only counts once the API confirms it.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + PaymentSagaIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.payment.outbox.poll-interval=200ms",
        "app.payment.provider=yookassa",
        "app.payment.yookassa.shop-id=123456",
        "app.payment.yookassa.secret-key=test_secret"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class YooKassaWebhookIntegrationTest {

    private static final String WEBHOOK = "/api/payments/webhooks/yookassa";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    @MockitoBean
    private PaymentGateway paymentGateway;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    @Qualifier("paymentExchange")
    private TopicExchange paymentExchange;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentNotificationRepository notificationRepository;

    @BeforeEach
    void provider() {
        when(paymentGateway.provider()).thenReturn(PaymentProvider.YOOKASSA);
    }

    @Test
    void verifiedNotificationCapturesThePayment() throws Exception {
        Queue probe = new Queue("test.probe." + UUID.randomUUID(), false, false, false);
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(paymentExchange)
                .with(MessagingConstants.PAYMENT_SUCCEEDED_KEY));
        UUID orderId = UUID.randomUUID();
        when(paymentGateway.createPayment(any())).thenReturn(new GatewayPayment("yk-1", Status.PENDING,
                new BigDecimal("4990.00"), "RUB", "ct-yk-1", null, null));

        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_PAYMENT_REQUESTED_KEY,
                new PaymentRequestedEvent(UUID.randomUUID(), Instant.now(), orderId, "ORD-YK", UUID.randomUUID(),
                        "jane@example.com", new BigDecimal("4990.00"), "RUB", "ONLINE"));
        long deadline = System.currentTimeMillis() + 15_000;
        while (paymentRepository.findByOrderId(orderId).map(Payment::getConfirmationToken).isEmpty()) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(200);
        }

        // The body claims success, but the API still reports the payment as pending: nothing changes
        when(paymentGateway.getPayment("yk-1")).thenReturn(new GatewayPayment("yk-1", Status.PENDING,
                new BigDecimal("4990.00"), "RUB", null, null, null));
        notify("payment.succeeded", "yk-1").andExpect(status().isOk());
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);

        when(paymentGateway.getPayment("yk-1")).thenReturn(new GatewayPayment("yk-1", Status.SUCCEEDED,
                new BigDecimal("4990.00"), "RUB", null, null, null));
        notify("payment.succeeded", "yk-1").andExpect(status().isOk());

        PaymentSucceededEvent event = rabbitTemplate.receiveAndConvert(probe.getName(), 10_000,
                new ParameterizedTypeReference<>() {
                });
        assertThat(event).isNotNull();
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.transactionId()).isEqualTo("yk-1");

        // YooKassa repeats the notification: recorded, not handled again
        notify("payment.succeeded", "yk-1").andExpect(status().isOk());
        assertThat(rabbitTemplate.receive(probe.getName(), 1_000)).isNull();
        assertThat(notificationRepository.findAll().stream()
                .filter(notification -> notification.getObjectId().equals("yk-1"))
                .map(PaymentNotification::getStatus))
                .containsExactly(NotificationStatus.IGNORED, NotificationStatus.PROCESSED, NotificationStatus.DUPLICATE);
        assertThat(notificationRepository.findAll()).filteredOn(notification -> notification.getObjectId().equals("yk-1"))
                .allSatisfy(notification -> assertThat(notification.getPayload())
                        .contains("\"event\":\"payment.succeeded\"", "\"paid\":true"));
    }

    @Test
    void forgedOrMalformedNotificationsAreRejected() throws Exception {
        when(paymentGateway.getPayment("forged")).thenThrow(GatewayException.rejected("HTTP 404 not_found"));
        when(paymentGateway.getPayment("later")).thenThrow(GatewayException.unavailable("timeout", null));

        notify("payment.succeeded", "forged").andExpect(status().isNotFound());
        // Temporary error: YooKassa sends the notification again
        notify("payment.succeeded", "later").andExpect(status().isServiceUnavailable());
        mockMvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"payment.succeeded\"}"))
                .andExpect(status().isBadRequest());
        notify("payout.succeeded", "po-1").andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions notify(String event, String id) throws Exception {
        return mockMvc.perform(post(WEBHOOK)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"notification","event":"%s","object":{"id":"%s","status":"succeeded","paid":true}}
                        """.formatted(event, id)));
    }
}
