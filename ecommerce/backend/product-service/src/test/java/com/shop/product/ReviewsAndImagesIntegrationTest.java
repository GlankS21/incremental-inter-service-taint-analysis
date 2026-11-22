package com.shop.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.OrderDeliveredEvent;
import com.shop.common.event.OrderItemPayload;
import com.shop.common.security.HmacJwtSupport;
import com.shop.common.security.JwtClaimNames;
import com.shop.common.security.JwtProperties;
import com.shop.product.messaging.consumer.OrderDeliveredConsumer;
import com.shop.product.repository.ProductPurchaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reviews (purchase recorded from {@code order.delivered} on RabbitMQ, rating summary, moderation)
 * and image upload/download against the real schema. Only seed product 1 receives reviews.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + ReviewsAndImagesIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.servlet.multipart.max-file-size=6MB",
        "spring.servlet.multipart.max-request-size=6MB"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReviewsAndImagesIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIz";
    private static final long PRODUCT_ID = 1L;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final Path IMAGE_DIR = createImageDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    @DynamicPropertySource
    static void imageStorage(DynamicPropertyRegistry registry) {
        registry.add("app.catalog.image-storage-dir", IMAGE_DIR::toString);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private OrderDeliveredConsumer orderDeliveredConsumer;
    @Autowired
    private ProductPurchaseRepository purchaseRepository;

    @Test
    void onlyBuyersReviewAndTheRatingSummaryFollows() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID secondBuyer = UUID.randomUUID();
        String buyerToken = token(buyer, "ROLE_CUSTOMER");

        mockMvc.perform(get("/api/reviews/products/" + PRODUCT_ID + "/mine").header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchased").value(false))
                .andExpect(jsonPath("$.data.canReview").value(false));
        mockMvc.perform(putReview(buyerToken, 4, "Хороший", "Анна"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("Отзыв могут оставить только покупатели, получившие этот товар"));

        // The order is delivered: order-service publishes order.delivered
        UUID orderId = UUID.randomUUID();
        OrderDeliveredEvent delivered = delivered(orderId, buyer, PRODUCT_ID, PRODUCT_ID, 99_999L);
        rabbitTemplate.convertAndSend(MessagingConstants.ORDER_EXCHANGE, MessagingConstants.ORDER_DELIVERED_KEY,
                delivered);
        awaitPurchases(orderId, 1);
        // A redelivery changes nothing
        orderDeliveredConsumer.onOrderDelivered(delivered);
        assertThat(purchaseRepository.countByOrderId(orderId)).isEqualTo(1);
        orderDeliveredConsumer.onOrderDelivered(delivered(UUID.randomUUID(), secondBuyer, PRODUCT_ID));

        mockMvc.perform(get("/api/reviews/products/" + PRODUCT_ID + "/mine").header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(jsonPath("$.data.purchased").value(true))
                .andExpect(jsonPath("$.data.canReview").value(true))
                .andExpect(jsonPath("$.data.review").doesNotExist());

        mockMvc.perform(putReview(buyerToken, 3, "  ", "  Анна  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Отзыв сохранён"))
                .andExpect(jsonPath("$.data.rating").value(3))
                .andExpect(jsonPath("$.data.authorName").value("Анна"))
                .andExpect(jsonPath("$.data.comment").doesNotExist());
        // Second write edits the same review
        mockMvc.perform(putReview(buyerToken, 4, "Хороший телефон", "Анна"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID));
        mockMvc.perform(putReview(token(secondBuyer, "ROLE_CUSTOMER"), 5, "Отлично", "Борис"))
                .andExpect(status().isOk());

        JsonNode product = getJson(get("/api/products/" + PRODUCT_ID));
        assertThat(product.at("/data/ratingAverage").decimalValue()).isEqualByComparingTo(new BigDecimal("4.5"));
        assertThat(product.at("/data/ratingCount").asInt()).isEqualTo(2);

        JsonNode reviews = getJson(get("/api/products/" + PRODUCT_ID + "/reviews"));
        assertThat(reviews.at("/data/totalElements").asInt()).isEqualTo(2);
        assertThat(reviews.at("/data/size").asInt()).isEqualTo(10);
        assertThat(reviews.at("/data/content/0/authorName").asText()).isEqualTo("Борис");
        assertThat(reviews.at("/data/content/0/userId").isMissingNode()).isTrue();

        // Sorting the catalogue by rating puts the only reviewed product first
        JsonNode best = getJson(get("/api/products").param("sort", "ratingAverage,desc").param("size", "1"));
        assertThat(best.at("/data/content/0/id").asLong()).isEqualTo(PRODUCT_ID);
        assertThat(best.at("/data/content/0/ratingCount").asInt()).isEqualTo(2);

        // Moderation
        mockMvc.perform(get("/api/reviews/admin").header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(status().isForbidden());
        JsonNode fiveStars = getJson(get("/api/reviews/admin").param("productId", String.valueOf(PRODUCT_ID))
                .param("rating", "5").header(HttpHeaders.AUTHORIZATION, adminToken()));
        assertThat(fiveStars.at("/data/totalElements").asInt()).isEqualTo(1);
        assertThat(fiveStars.at("/data/content/0/userId").asText()).isEqualTo(secondBuyer.toString());
        assertThat(fiveStars.at("/data/content/0/productSlug").asText()).isNotBlank();
        long fiveStarId = fiveStars.at("/data/content/0/id").asLong();
        mockMvc.perform(delete("/api/reviews/admin/" + fiveStarId).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Отзыв удалён"));
        product = getJson(get("/api/products/" + PRODUCT_ID));
        assertThat(product.at("/data/ratingAverage").decimalValue()).isEqualByComparingTo(new BigDecimal("4.0"));
        assertThat(product.at("/data/ratingCount").asInt()).isEqualTo(1);

        // The buyer removes the own review
        mockMvc.perform(delete("/api/reviews/products/" + PRODUCT_ID).header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/reviews/products/" + PRODUCT_ID).header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(status().isNotFound());
        product = getJson(get("/api/products/" + PRODUCT_ID));
        assertThat(product.at("/data/ratingAverage").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(product.at("/data/ratingCount").asInt()).isZero();
    }

    @Test
    void reviewEndpointsEnforceAccessVisibilityAndValidation() throws Exception {
        mockMvc.perform(put("/api/reviews/products/" + PRODUCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"authorName\":\"X\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reviews/products/" + PRODUCT_ID + "/mine")).andExpect(status().isUnauthorized());

        // DRAFT product 23 and unknown products have no public reviews
        mockMvc.perform(get("/api/products/23/reviews")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/9999/reviews")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/" + PRODUCT_ID + "/reviews").param("sort", "userId"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(putReview(token(UUID.randomUUID(), "ROLE_CUSTOMER"), 6, null, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(2));
    }

    @Test
    void adminUploadsImageThatIsServedPubliclyAndUsableInProducts() throws Exception {
        MvcResult upload = mockMvc.perform(multipart("/api/products/images")
                        .file(new MockMultipartFile("file", "photo.png", MediaType.IMAGE_PNG_VALUE, PNG))
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Изображение загружено"))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.size").value(PNG.length))
                .andReturn();
        String url = objectMapper.readTree(upload.getResponse().getContentAsString()).at("/data/url").asText();
        assertThat(url).matches("^/api/products/images/[0-9a-f-]{36}\\.png$");

        // Served even when the client asks for JSON (the frontend relay does)
        mockMvc.perform(get(url).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // The relative URL is accepted as a product image
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", "TST-UPLOAD-1");
        body.put("name", "Товар с загруженным изображением");
        body.put("price", 1000);
        body.put("categoryId", 8);
        body.put("images", List.of(Map.of("url", url)));
        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.images[0].url").value(url))
                .andExpect(jsonPath("$.data.ratingCount").value(0));
    }

    @Test
    void uploadRejectsNonImagesAndNonAdmins() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "fake.png", MediaType.IMAGE_PNG_VALUE,
                "not an image at all".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/products/images").file(fake).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Допустимы только изображения JPEG, PNG, WebP и GIF"));
        mockMvc.perform(multipart("/api/products/images").header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Выберите непустой файл изображения"));

        MockMultipartFile png = new MockMultipartFile("file", "photo.png", MediaType.IMAGE_PNG_VALUE, PNG);
        mockMvc.perform(multipart("/api/products/images").file(png)
                        .header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), "ROLE_CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/products/images").file(png)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/products/images/not-a-generated-name.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/images/00000000-0000-0000-0000-000000000000.png"))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder putReview(String token, Integer rating, String comment, String authorName)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rating", rating);
        body.put("comment", comment);
        body.put("authorName", authorName);
        return put("/api/reviews/products/" + PRODUCT_ID)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private void awaitPurchases(UUID orderId, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (purchaseRepository.countByOrderId(orderId) < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(purchaseRepository.countByOrderId(orderId)).isEqualTo(expected);
    }

    private static OrderDeliveredEvent delivered(UUID orderId, UUID userId, Long... productIds) {
        List<OrderItemPayload> items = java.util.Arrays.stream(productIds)
                .map(id -> new OrderItemPayload(id, "SKU-" + id, "Product " + id, 1, new BigDecimal("1000")))
                .toList();
        return new OrderDeliveredEvent(UUID.randomUUID(), Instant.now(), orderId, "ORD-TEST", userId,
                "buyer@example.com", items);
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String adminToken() {
        return token(UUID.randomUUID(), "ROLE_ADMIN");
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
                .claim(JwtClaimNames.EMAIL, "someone@example.com")
                .claim(JwtClaimNames.ROLES, List.of(role))
                .claim(JwtClaimNames.TOKEN_TYPE, JwtClaimNames.ACCESS_TOKEN_TYPE)
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(HmacJwtSupport.ALGORITHM).build(), claims)).getTokenValue();
    }

    private static Path createImageDir() {
        try {
            return Files.createTempDirectory("product-images-it");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
