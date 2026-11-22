package com.shop.order.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.shop.common.event.OrderCancelledEvent;
import com.shop.order.config.OrderProperties;
import com.shop.order.entity.OutboxEvent;
import com.shop.order.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OrderProperties properties = new OrderProperties(null, null, null, null, null, null, null);
        publisher = new OutboxPublisher(outboxEventRepository, rabbitTemplate, objectMapper, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void poisonRowIsParkedAndDoesNotBlockLaterEvents() throws Exception {
        OutboxEvent poison = row("com.shop.common.event.RemovedEvent", "{}");
        OutboxEvent valid = validRow();
        when(outboxEventRepository.lockNextPending(anyInt())).thenReturn(List.of(poison, valid));

        int published = publisher.publishPending();

        assertThat(published).isEqualTo(1);
        assertThat(valid.getPublishedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(poison.getFailedAt()).isEqualTo(NOW);
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).contains("RemovedEvent");
        verify(rabbitTemplate, times(1)).invoke(any(RabbitOperations.OperationsCallback.class));
    }

    @Test
    void untrustedTypeIsParkedWithoutBeingLoaded() throws Exception {
        OutboxEvent untrusted = row("java.lang.Runtime", "{}");
        OutboxEvent valid = validRow();
        when(outboxEventRepository.lockNextPending(anyInt())).thenReturn(List.of(untrusted, valid));

        publisher.publishPending();

        assertThat(untrusted.getFailedAt()).isEqualTo(NOW);
        assertThat(valid.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void brokerOutageKeepsTheRowPendingAndStopsTheBatch() throws Exception {
        OutboxEvent first = validRow();
        OutboxEvent second = validRow();
        when(outboxEventRepository.lockNextPending(anyInt())).thenReturn(List.of(first, second));
        when(rabbitTemplate.invoke(any(RabbitOperations.OperationsCallback.class)))
                .thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        int published = publisher.publishPending();

        assertThat(published).isZero();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getFailedAt()).as("a broker outage must never park an event").isNull();
        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isZero();
        verify(rabbitTemplate, times(1)).invoke(any(RabbitOperations.OperationsCallback.class));
    }

    private OutboxEvent validRow() throws Exception {
        OrderCancelledEvent event = new OrderCancelledEvent(UUID.randomUUID(), NOW, UUID.randomUUID(),
                "ORD-1", UUID.randomUUID(), "buyer@example.com", "test");
        return row(OrderCancelledEvent.class.getName(), objectMapper.writeValueAsString(event));
    }

    private static OutboxEvent row(String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId("ORD-1");
        event.setExchange("order.exchange");
        event.setRoutingKey("order.cancelled");
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreatedAt(NOW);
        return event;
    }
}
