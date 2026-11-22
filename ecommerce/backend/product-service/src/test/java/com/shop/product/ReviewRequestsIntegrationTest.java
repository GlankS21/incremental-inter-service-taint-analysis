package com.shop.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.OrderDeliveredEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.common.event.ReviewReminderDueEvent;
import com.shop.common.event.ReviewReminderItem;
import com.shop.common.security.HmacJwtSupport;
import com.shop.common.security.JwtClaimNames;
import com.shop.common.security.JwtProperties;
import com.shop.product.messaging.consumer.OrderDeliveredConsumer;
import com.shop.product.service.ReviewReminderService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer's review requests: products waiting for a review, the caller's reviews, review state of
 * several products at once, and the reminder published a few days after delivery. Every endpoint only
 * answers with the caller's own data. The reminder job is disabled: the test runs it itself.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + ReviewRequestsIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.reviews.reminder.enabled=false",
        "app.reviews.reminder.delay-days=3"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReviewRequestsIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIz";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderDeliveredConsumer orderDeliveredConsumer;
    @Autowired
    private ReviewReminderService reviewReminderService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pendingReviewsStatusesAndCountsBelongToTheCallerOnly() throws Exception {
        UUID anna = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        String annaToken = token(anna);
        String borisToken = token(boris);
        UUID firstOrder = UUID.randomUUID();
        UUID secondOrder = UUID.randomUUID();
        UUID borisOrder = UUID.randomUUID();
        Instant now = Instant.now();
        orderDeliveredConsumer.onOrderDelivered(delivered(firstOrder, anna, now.minus(Duration.ofHours(2)), 2L, 3L));
        // Product 2 bought again: listed once, with its latest delivery
        orderDeliveredConsumer.onOrderDelivered(delivered(secondOrder, anna, now.minus(Duration.ofHours(1)), 4L, 2L));
        orderDeliveredConsumer.onOrderDelivered(delivered(borisOrder, boris, now, 5L));

        JsonNode pending = data(get("/api/reviews/pending").header(HttpHeaders.AUTHORIZATION, annaToken));
        assertThat(pending.get("totalElements").asLong()).isEqualTo(3);
        Map<Long, JsonNode> byProduct = byProductId(pending.get("content"));
        assertThat(byProduct).containsOnlyKeys(2L, 3L, 4L);
        assertThat(byProduct.get(2L).get("orderId").asText()).isEqualTo(secondOrder.toString());
        assertThat(byProduct.get(3L).get("orderId").asText()).isEqualTo(firstOrder.toString());
        assertThat(byProduct.get(2L).get("slug").asText()).isEqualTo(slugOf(2L));
        assertThat(byProduct.get(2L).get("name").asText()).isEqualTo(nameOf(2L));
        assertThat(byProduct.get(2L).get("deliveredAt").asText()).isNotBlank();
        // Newest delivery first: the products of the second order come before product 3
        assertThat(pending.get("content").get(2).get("productId").asLong()).isEqualTo(3L);
        assertThat(byProductId(data(get("/api/reviews/pending")
                .header(HttpHeaders.AUTHORIZATION, borisToken)).get("content"))).containsOnlyKeys(5L);

        // Counts by order: Boris' order asked by Anna is simply absent
        JsonNode count = data(get("/api/reviews/pending/count")
                .param("orderIds", firstOrder + "," + secondOrder + "," + borisOrder)
                .header(HttpHeaders.AUTHORIZATION, annaToken));
        assertThat(count.get("count").asLong()).isEqualTo(3);
        assertThat(count.get("byOrder").get(firstOrder.toString()).asLong()).isEqualTo(2);
        assertThat(count.get("byOrder").get(secondOrder.toString()).asLong()).isEqualTo(2);
        assertThat(count.get("byOrder").has(borisOrder.toString())).isFalse();
        mockMvc.perform(get("/api/reviews/pending/count").header(HttpHeaders.AUTHORIZATION, borisToken))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.byOrder").isEmpty());

        JsonNode statuses = data(get("/api/reviews/status").param("productIds", "2,3,5,999,2")
                .header(HttpHeaders.AUTHORIZATION, annaToken));
        assertThat(statuses).hasSize(4);
        assertThat(statuses.get(0).get("productId").asLong()).isEqualTo(2L);
        assertThat(statuses.get(0).get("purchased").asBoolean()).isTrue();
        assertThat(statuses.get(0).get("canReview").asBoolean()).isTrue();
        assertThat(statuses.get(0).get("reviewed").asBoolean()).isFalse();
        assertThat(statuses.get(0).get("rating").isNull()).isTrue();
        // Boris' purchase is not Anna's; an unknown product is simply not purchased
        assertThat(statuses.get(2).get("productId").asLong()).isEqualTo(5L);
        assertThat(statuses.get(2).get("purchased").asBoolean()).isFalse();
        assertThat(statuses.get(3).get("purchased").asBoolean()).isFalse();

        // The product page tells when the caller received the product (latest delivery)
        mockMvc.perform(get("/api/reviews/products/2/mine").header(HttpHeaders.AUTHORIZATION, annaToken))
                .andExpect(jsonPath("$.data.purchased").value(true))
                .andExpect(jsonPath("$.data.purchasedAt").value(
                        startsWith(now.minus(Duration.ofHours(1)).toString().substring(0, 16))));
        mockMvc.perform(get("/api/reviews/products/5/mine").header(HttpHeaders.AUTHORIZATION, annaToken))
                .andExpect(jsonPath("$.data.purchased").value(false))
                .andExpect(jsonPath("$.data.purchasedAt").doesNotExist());

        mockMvc.perform(putReview(annaToken, 2L, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedPurchase").value(true));

        JsonNode afterReview = data(get("/api/reviews/pending/count")
                .param("orderIds", firstOrder + "," + secondOrder)
                .header(HttpHeaders.AUTHORIZATION, annaToken));
        assertThat(afterReview.get("count").asLong()).isEqualTo(2);
        assertThat(afterReview.get("byOrder").get(firstOrder.toString()).asLong()).isEqualTo(1);
        assertThat(afterReview.get("byOrder").get(secondOrder.toString()).asLong()).isEqualTo(1);
        mockMvc.perform(get("/api/reviews/status").param("productIds", "2").header(HttpHeaders.AUTHORIZATION, annaToken))
                .andExpect(jsonPath("$.data[0].reviewed").value(true))
                .andExpect(jsonPath("$.data[0].rating").value(4));
        mockMvc.perform(get("/api/products/2/reviews"))
                .andExpect(jsonPath("$.data.content[0].verifiedPurchase").value(true));

        mockMvc.perform(get("/api/reviews/mine").header(HttpHeaders.AUTHORIZATION, annaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].productId").value(2))
                .andExpect(jsonPath("$.data.content[0].productName").value(nameOf(2L)))
                .andExpect(jsonPath("$.data.content[0].productSlug").value(slugOf(2L)))
                .andExpect(jsonPath("$.data.content[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.content[0].rating").value(4))
                .andExpect(jsonPath("$.data.content[0].verifiedPurchase").value(true));
        mockMvc.perform(get("/api/reviews/mine").header(HttpHeaders.AUTHORIZATION, borisToken))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        // Withdrawn products: no longer waiting for a review, and the review is shown as hidden
        jdbcTemplate.update("update products set status = 'ARCHIVED' where id in (2, 3)");
        try {
            assertThat(byProductId(data(get("/api/reviews/pending").header(HttpHeaders.AUTHORIZATION, annaToken))
                    .get("content"))).containsOnlyKeys(4L);
            mockMvc.perform(get("/api/reviews/status").param("productIds", "3").header(HttpHeaders.AUTHORIZATION, annaToken))
                    .andExpect(jsonPath("$.data[0].purchased").value(true))
                    .andExpect(jsonPath("$.data[0].canReview").value(false));
            mockMvc.perform(get("/api/reviews/mine").header(HttpHeaders.AUTHORIZATION, annaToken))
                    .andExpect(jsonPath("$.data.content[0].status").value("HIDDEN"));
        } finally {
            jdbcTemplate.update("update products set status = 'ACTIVE' where id in (2, 3)");
        }
    }

    @Test
    void reviewRequestEndpointsRequireAnAccountAndValidTheirInput() throws Exception {
        for (String path : List.of("/api/reviews/pending", "/api/reviews/pending/count", "/api/reviews/mine",
                "/api/reviews/status?productIds=1")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        String token = token(UUID.randomUUID());
        String tooManyProducts = LongStream.rangeClosed(1, 51).mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        mockMvc.perform(get("/api/reviews/status").param("productIds", tooManyProducts)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("За один запрос можно узнать состояние не более 50 товаров"));
        mockMvc.perform(get("/api/reviews/status").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reviews/status").param("productIds", "abc").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            orders.add(UUID.randomUUID().toString());
        }
        mockMvc.perform(get("/api/reviews/pending/count").param("orderIds", String.join(",", orders))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("За один запрос можно посчитать отзывы не более чем по 50 заказам"));
        mockMvc.perform(get("/api/reviews/mine").param("sort", "comment").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reviews/pending").param("size", "500").param("sort", "name")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @Test
    void oneReminderPerDeliveredOrderListsOnlyTheProductsStillToReview() throws Exception {
        UUID vera = UUID.randomUUID();
        String veraToken = token(vera);
        Instant fourDaysAgo = Instant.now().minus(Duration.ofDays(4));
        UUID dueOrder = UUID.randomUUID();
        UUID reviewedOrder = UUID.randomUUID();
        UUID recentOrder = UUID.randomUUID();
        orderDeliveredConsumer.onOrderDelivered(delivered(dueOrder, vera, fourDaysAgo, 6L, 7L));
        orderDeliveredConsumer.onOrderDelivered(delivered(reviewedOrder, vera, fourDaysAgo, 8L));
        orderDeliveredConsumer.onOrderDelivered(delivered(recentOrder, vera, Instant.now().minus(Duration.ofDays(1)), 9L));
        mockMvc.perform(putReview(veraToken, 6L, 5)).andExpect(status().isOk());
        mockMvc.perform(putReview(veraToken, 8L, 3)).andExpect(status().isOk());

        String probe = amqpAdmin.declareQueue(new Queue("review-reminder-probe-" + UUID.randomUUID(), false, false, false));
        amqpAdmin.declareBinding(new Binding(probe, Binding.DestinationType.QUEUE, MessagingConstants.PRODUCT_EXCHANGE,
                MessagingConstants.REVIEW_REMINDER_DUE_KEY, null));

        assertThat(reviewReminderService.publishDueReminders()).isEqualTo(1);
        ReviewReminderDueEvent reminder = rabbitTemplate.receiveAndConvert(probe, 10_000,
                new ParameterizedTypeReference<ReviewReminderDueEvent>() {
                });
        assertThat(reminder).isNotNull();
        assertThat(reminder.orderId()).isEqualTo(dueOrder);
        assertThat(reminder.userId()).isEqualTo(vera);
        assertThat(reminder.orderNumber()).isEqualTo("ORD-" + dueOrder.toString().substring(0, 8));
        assertThat(reminder.eventId()).isEqualTo(UUID.nameUUIDFromBytes(
                ("review-reminder:" + dueOrder).getBytes(StandardCharsets.UTF_8)));
        assertThat(reminder.items()).extracting(ReviewReminderItem::productId).containsExactly(7L);
        assertThat(reminder.items().getFirst().productSlug()).isEqualTo(slugOf(7L));

        // Each order is handled once: nothing more now, and the recent order waits for its turn
        assertThat(reviewReminderService.publishDueReminders()).isZero();
        assertThat(rabbitTemplate.receive(probe, 1_000)).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from product_purchases
                where order_id = ? and review_reminder_processed_at is null
                """, Long.class, recentOrder)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from product_purchases
                where order_id in (?, ?) and review_reminder_processed_at is not null
                """, Long.class, dueOrder, reviewedOrder)).isEqualTo(3);
    }

    private OrderDeliveredEvent delivered(UUID orderId, UUID userId, Instant at, Long... productIds) {
        List<OrderItemPayload> items = Arrays.stream(productIds)
                .map(id -> new OrderItemPayload(id, "SKU-" + id, "Product " + id, 1, new BigDecimal("1000")))
                .toList();
        return new OrderDeliveredEvent(UUID.randomUUID(), at, orderId, "ORD-" + orderId.toString().substring(0, 8),
                userId, "customer@example.com", items);
    }

    private MockHttpServletRequestBuilder putReview(String token, long productId, int rating) throws Exception {
        return put("/api/reviews/products/" + productId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("rating", rating, "authorName", "Покупатель")));
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private static Map<Long, JsonNode> byProductId(JsonNode content) {
        return StreamSupport.stream(content.spliterator(), false)
                .collect(Collectors.toMap(node -> node.get("productId").asLong(), node -> node));
    }

    private String slugOf(long productId) {
        return jdbcTemplate.queryForObject("select slug from products where id = ?", String.class, productId);
    }

    private String nameOf(long productId) {
        return jdbcTemplate.queryForObject("select name from products where id = ?", String.class, productId);
    }

    private String token(UUID subject) {
        JwtProperties properties = new JwtProperties(SECRET, "test-issuer");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(HmacJwtSupport.secretKey(properties)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(JwtClaimNames.EMAIL, "someone@example.com")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_CUSTOMER"))
                .claim(JwtClaimNames.TOKEN_TYPE, JwtClaimNames.ACCESS_TOKEN_TYPE)
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(HmacJwtSupport.ALGORITHM).build(), claims)).getTokenValue();
    }
}
