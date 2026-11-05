package com.shop.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.UserRegisteredEvent;
import com.shop.common.security.HmacJwtSupport;
import com.shop.common.security.JwtClaimNames;
import com.shop.common.security.JwtProperties;
import com.shop.common.exception.BusinessException;
import com.shop.user.dto.request.AddressRequest;
import com.shop.user.dto.response.AddressResponse;
import com.shop.user.dto.response.UserProfileResponse;
import com.shop.user.repository.UserProfileRepository;
import com.shop.user.service.AddressService;
import com.shop.user.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * user.registered consumption and the address book API against real PostgreSQL and RabbitMQ.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + UserFlowIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.placeholders.[admin_email]=admin@shop.local"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class UserFlowIntegrationTest {

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
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private UserProfileService userProfileService;
    @Autowired
    private AddressService addressService;

    @Test
    void registrationEventCreatesProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        rabbitTemplate.convertAndSend(MessagingConstants.USER_EXCHANGE, MessagingConstants.USER_REGISTERED_KEY,
                new UserRegisteredEvent(UUID.randomUUID(), Instant.now(), userId, "event@example.com", "Eve", "Nt"));

        waitUntil(() -> userProfileRepository.existsById(userId));

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(userId, "event@example.com", "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Eve"))
                .andExpect(jsonPath("$.data.email").value("event@example.com"));
    }

    @Test
    void addressBookKeepsExactlyOneDefault() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = bearer(userId, "book@example.com", "ROLE_CUSTOMER");

        String first = createAddress(token, "First", false).at("/data/id").asText();
        JsonNode second = createAddress(token, "Second", true);
        assertThat(second.at("/data/defaultAddress").asBoolean()).isTrue();

        JsonNode list = getJson("/api/users/me/addresses", token);
        assertThat(list.at("/data").size()).isEqualTo(2);
        assertThat(list.at("/data/0/recipientName").asText()).isEqualTo("Second");
        assertThat(list.at("/data/1/defaultAddress").asBoolean()).isFalse();

        mockMvc.perform(delete("/api/users/me/addresses/" + second.at("/data/id").asText())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        JsonNode afterDelete = getJson("/api/users/me/addresses", token);
        assertThat(afterDelete.at("/data").size()).isEqualTo(1);
        assertThat(afterDelete.at("/data/0/id").asText()).isEqualTo(first);
        assertThat(afterDelete.at("/data/0/defaultAddress").asBoolean()).isTrue();

        // Another user cannot see this address
        mockMvc.perform(get("/api/users/me/addresses/" + first)
                        .header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), "other@example.com", "ROLE_CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Адрес не найден"));
    }

    @Test
    void invalidAddressIsRejectedWithViolations() throws Exception {
        String token = bearer(UUID.randomUUID(), "invalid@example.com", "ROLE_CUSTOMER");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientName", "");
        body.put("phoneNumber", "abc");
        body.put("addressLine1", "Street");
        body.put("city", "City");
        body.put("countryCode", "russia");

        mockMvc.perform(post("/api/users/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Проверьте правильность заполнения полей"))
                .andExpect(jsonPath("$.violations.length()").value(3))
                .andExpect(jsonPath("$.violations[?(@.field == 'phoneNumber')].message")
                        .value("Укажите номер телефона в формате +7XXXXXXXXXX"))
                .andExpect(jsonPath("$.violations[?(@.field == 'countryCode')].message")
                        .value("Укажите двухбуквенный код страны ISO 3166-1, например RU"))
                .andExpect(jsonPath("$.violations[?(@.field == 'recipientName')].message")
                        .value("не должно быть пустым"));
    }

    @Test
    void phoneNumberMustBeRussian() throws Exception {
        String token = bearer(UUID.randomUUID(), "foreign-phone@example.com", "ROLE_CUSTOMER");

        for (String phone : List.of("+12025550123", "89161234567", "+7916123456", "+791612345678")) {
            mockMvc.perform(put("/api/users/me")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "firstName", "Иван", "lastName", "Петров", "phoneNumber", phone))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].field").value("phoneNumber"))
                    .andExpect(jsonPath("$.violations[0].message")
                            .value("Укажите номер телефона в формате +7XXXXXXXXXX"));
        }
        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "firstName", "Иван", "lastName", "Петров", "phoneNumber", "+79161234567"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Профиль обновлён"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+79161234567"));
    }

    @Test
    void addressWithoutCountryIsInRussia() throws Exception {
        String token = bearer(UUID.randomUUID(), "no-country@example.com", "ROLE_CUSTOMER");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientName", "Иван Петров");
        body.put("phoneNumber", "+79161234567");
        body.put("addressLine1", "ул. Тверская, д. 7, кв. 12");
        body.put("city", "Москва");
        body.put("postalCode", "125009");

        mockMvc.perform(post("/api/users/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Адрес добавлен"))
                .andExpect(jsonPath("$.data.countryCode").value("RU"));
    }

    @Test
    void adminListingRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION,
                        bearer(UUID.randomUUID(), "customer@example.com", "ROLE_CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION,
                        bearer(UUID.randomUUID(), "admin@example.com", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSeesTheAddressBookOfAUser() throws Exception {
        UUID userId = UUID.randomUUID();
        String customer = bearer(userId, "book-owner@example.com", "ROLE_CUSTOMER");
        createAddress(customer, "Owner Home", true);

        mockMvc.perform(get("/api/users/{userId}/addresses", userId).header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/{userId}/addresses", userId).header(HttpHeaders.AUTHORIZATION,
                        bearer(UUID.randomUUID(), "admin@example.com", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].recipientName").value("Owner Home"))
                .andExpect(jsonPath("$.data[0].defaultAddress").value(true));
    }

    @Test
    void parallelAddressCreationRespectsTheLimitAndKeepsOneDefault() throws Exception {
        UUID userId = UUID.randomUUID();

        List<Future<AddressResponse>> results = runConcurrently(AddressService.MAX_ADDRESSES_PER_USER + 2,
                () -> addressService.createAddress(userId, "parallel@example.com", addressRequest(false)));

        List<Throwable> failures = failures(results);
        List<AddressResponse> addresses = addressService.listAddresses(userId);
        assertThat(addresses).hasSize(results.size() - failures.size()).hasSizeLessThanOrEqualTo(
                AddressService.MAX_ADDRESSES_PER_USER);
        assertThat(failures).allSatisfy(failure -> assertThat(failure)
                .isInstanceOf(BusinessException.class)
                .hasMessage("address.error.limit-reached"));
        assertThat(addresses).filteredOn(AddressResponse::defaultAddress).hasSize(1);
    }

    @Test
    void parallelFirstAddressesAreBothCreated() throws Exception {
        UUID userId = UUID.randomUUID();
        userProfileService.getOrCreateProfile(userId, "first-parallel@example.com");

        List<Future<AddressResponse>> results = runConcurrently(2,
                () -> addressService.createAddress(userId, "first-parallel@example.com", addressRequest(false)));

        assertThat(failures(results)).isEmpty();
        assertThat(addressService.listAddresses(userId)).hasSize(2)
                .filteredOn(AddressResponse::defaultAddress).hasSize(1);
    }

    @Test
    void parallelDefaultChangesKeepOneDefault() throws Exception {
        UUID userId = UUID.randomUUID();
        addressService.createAddress(userId, "default-parallel@example.com", addressRequest(false));
        UUID second = addressService.createAddress(userId, "default-parallel@example.com", addressRequest(false)).id();
        UUID third = addressService.createAddress(userId, "default-parallel@example.com", addressRequest(false)).id();
        List<UUID> targets = List.of(second, third);
        AtomicInteger next = new AtomicInteger();

        List<Future<AddressResponse>> results = runConcurrently(targets.size(),
                () -> addressService.setDefaultAddress(userId, targets.get(next.getAndIncrement())));

        assertThat(failures(results)).isEmpty();
        assertThat(addressService.listAddresses(userId)).filteredOn(AddressResponse::defaultAddress).hasSize(1);
    }

    @Test
    void parallelFirstAccessesShareOneProfile() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            UUID userId = UUID.randomUUID();

            List<Future<UserProfileResponse>> results = runConcurrently(4,
                    () -> userProfileService.getOrCreateProfile(userId, "First.Access@example.com"));

            assertThat(failures(results)).isEmpty();
            for (Future<UserProfileResponse> result : results) {
                assertThat(result.get().id()).isEqualTo(userId);
                assertThat(result.get().email()).isEqualTo("first.access@example.com");
            }
        }
    }

    @Test
    void adminSearchTreatsLikeWildcardsLiterally() throws Exception {
        UUID underscore = UUID.randomUUID();
        UUID percent = UUID.randomUUID();
        userProfileService.getOrCreateProfile(underscore, "under_score@example.com");
        userProfileService.getOrCreateProfile(percent, "hundred%@example.com");
        userProfileService.getOrCreateProfile(UUID.randomUUID(), "plain@example.com");
        String admin = bearer(UUID.randomUUID(), "admin@example.com", "ROLE_ADMIN");

        assertThat(searchProfileIds("_", admin)).containsExactly(underscore.toString());
        assertThat(searchProfileIds("%", admin)).containsExactly(percent.toString());
    }

    private List<String> searchProfileIds(String keyword, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users").param("keyword", keyword).param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/content")
                .forEach(profile -> ids.add(profile.get("id").asText()));
        return ids;
    }

    private <T> List<Future<T>> runConcurrently(int threads, Callable<T> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Throwable> failures(List<? extends Future<?>> results) throws InterruptedException {
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> result : results) {
            try {
                result.get();
            } catch (ExecutionException ex) {
                failures.add(ex.getCause());
            }
        }
        return failures;
    }

    private static AddressRequest addressRequest(boolean makeDefault) {
        return new AddressRequest("Иван Петров", "+79161234567", "ул. Тверская, д. 7", null, null, null,
                "Москва", "125009", "RU", makeDefault);
    }

    private JsonNode createAddress(String token, String recipient, boolean makeDefault) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientName", recipient);
        body.put("phoneNumber", "+79161234567");
        body.put("addressLine1", "ул. Тверская, д. 7");
        body.put("city", "Москва");
        body.put("countryCode", "RU");
        body.put("defaultAddress", makeDefault);
        MvcResult result = mockMvc.perform(post("/api/users/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(UUID userId, String email, String role) {
        JwtProperties properties = new JwtProperties(SECRET, "test-issuer");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(HmacJwtSupport.secretKey(properties)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(JwtClaimNames.EMAIL, email)
                .claim(JwtClaimNames.ROLES, List.of(role))
                .claim(JwtClaimNames.TOKEN_TYPE, JwtClaimNames.ACCESS_TOKEN_TYPE)
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(HmacJwtSupport.ALGORITHM).build(), claims)).getTokenValue();
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not met within 15 seconds");
            }
            Thread.sleep(200);
        }
    }
}
