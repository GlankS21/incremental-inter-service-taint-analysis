package com.shop.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.shop.common.constant.MessagingConstants;
import com.shop.common.event.StockLevelChangedEvent;
import com.shop.common.security.HmacJwtSupport;
import com.shop.common.security.JwtClaimNames;
import com.shop.common.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalogue search against the real Flyway schema (full-text configurations, triggers, dictionary,
 * synonyms) and the Russian sample catalogue: morphology, typos, keyboard layout, synonyms, ranking,
 * facets, type-ahead, search log, synonym administration and the stock events of inventory-service.
 * Tests that change products restore them.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + SearchIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SearchIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIz";
    private static final int ACTIVE_SEED_PRODUCTS = 22;

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void findsInflectedRussianWords() throws Exception {
        JsonNode headphones = search("наушников");
        assertThat(skus(headphones)).contains("AUD-APP2-USBC", "AUD-WH1000XM5", "AUD-BUDS2PRO");
        assertThat(headphones.at("/data/correctionApplied").asBoolean()).isFalse();
        assertThat(headphones.at("/data/keyword").asText()).isEqualTo("наушников");

        // "телефоны" reaches the smartphones through the stem and the synonym "смартфон"
        assertThat(skus(search("телефоны"))).contains("PHN-IP15-128", "PHN-S24-256", "PHN-PX8-128", "PHN-RN13-128");
        // The word being typed matches as a prefix
        assertThat(skus(search("клавиат"))).contains("ACC-K380", "ACC-KCK2");
    }

    @Test
    void correctsTyposAndTheKeyboardLayout() throws Exception {
        JsonNode typo = search("самсунк");
        assertThat(typo.at("/data/correctionApplied").asBoolean()).isTrue();
        assertThat(typo.at("/data/correctedKeyword").asText()).isEqualTo("samsung");
        assertThat(skus(typo)).contains("PHN-S24-256", "TAB-TABS9-128", "AUD-BUDS2PRO");

        JsonNode layout = search("ntktajy");
        assertThat(layout.at("/data/correctionApplied").asBoolean()).isTrue();
        assertThat(layout.at("/data/correctedKeyword").asText()).isEqualTo("телефон");
        assertThat(skus(layout)).contains("PHN-IP15-128");

        // A misspelt word among correct ones: trigram similarity with the product name
        assertThat(skus(search("logitek"))).contains("ACC-MXM3S", "ACC-K380");

        JsonNode nothing = search("qzxjvwq");
        assertThat(nothing.at("/data/totalElements").asInt()).isZero();
        assertThat(nothing.at("/data/correctedKeyword").isNull()).isTrue();
    }

    @Test
    void usesSynonyms() throws Exception {
        JsonNode laptops = search("лэптоп");
        assertThat(skus(laptops)).contains("LAP-MBA-M2", "LAP-XPS13-I7", "LAP-TPE14-I5", "LAP-ROG-G14",
                "LAP-LEG5-4060", "LAP-NITRO5-3050");
        assertThat(skus(search("айфон"))).contains("PHN-IP15-128");
        assertThat(skus(search("iphone")).getFirst()).isEqualTo("PHN-IP15-128");
    }

    @Test
    void ranksByRelevancePopularityAndStock() throws Exception {
        try {
            jdbcTemplate.update("update products set sales_count = 500 where id = 4");
            assertThat(skus(search("смартфон")).getFirst()).isEqualTo("PHN-RN13-128");

            jdbcTemplate.update("update products set in_stock = false where id = 4");
            JsonNode outOfStock = search("смартфон");
            assertThat(skus(outOfStock).getFirst()).isNotEqualTo("PHN-RN13-128");
            assertThat(skus(outOfStock)).contains("PHN-RN13-128");

            // An explicit sort replaces the relevance
            JsonNode byPrice = getJson(get("/api/products").param("keyword", "смартфон")
                    .param("categoryId", "2").param("sort", "price,asc"));
            assertThat(skus(byPrice).getFirst()).isEqualTo("PHN-RN13-128");
            // Without keyword, "relevance" means best sellers first
            JsonNode bestSellers = getJson(get("/api/products").param("sort", "relevance"));
            assertThat(bestSellers.at("/data/content/0/sku").asText()).isEqualTo("PHN-RN13-128");
            assertThat(bestSellers.at("/data/content/0/salesCount").asInt()).isEqualTo(500);
        } finally {
            jdbcTemplate.update("update products set sales_count = 0, in_stock = true where id = 4");
        }
    }

    @Test
    void treatsSqlAndQuerySyntaxAsText() throws Exception {
        for (String keyword : List.of("'; drop table products; --", "телефон' or '1'='1", "a & | ! ( ) : * <-> \\",
                "%_%", "\"apple\" -iphone or")) {
            mockMvc.perform(get("/api/products").param("keyword", keyword)).andExpect(status().isOk());
            mockMvc.perform(get("/api/products/suggest").param("q", keyword)).andExpect(status().isOk());
        }
        assertThat(getJson(get("/api/products").param("size", "100")).at("/data/totalElements").asInt())
                .isEqualTo(ACTIVE_SEED_PRODUCTS);

        mockMvc.perform(get("/api/products").param("keyword", "a".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Поисковый запрос не должен быть длиннее 100 символов"));
        mockMvc.perform(get("/api/products/suggest").param("q", "a".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsDisjunctiveFacets() throws Exception {
        JsonNode laptops = search("ноутбук");
        assertThat(facetCount(laptops.at("/data/facets/categories"), "name", "Игровые ноутбуки")).isEqualTo(3);
        assertThat(facetCount(laptops.at("/data/facets/categories"), "name", "Офисные ноутбуки")).isEqualTo(3);
        assertThat(facetCount(laptops.at("/data/facets/brands"), "name", "Lenovo")).isEqualTo(2);
        assertThat(laptops.at("/data/facets/priceRanges").size()).isPositive();
        assertThat(laptops.at("/data/facets/minPrice").decimalValue())
                .isLessThanOrEqualTo(laptops.at("/data/facets/maxPrice").decimalValue());

        // The brand facet ignores the brand filter, the other facets do not
        JsonNode lenovo = getJson(get("/api/products").param("keyword", "ноутбук").param("brand", "lenovo"));
        assertThat(lenovo.at("/data/totalElements").asInt()).isEqualTo(2);
        assertThat(facetCount(lenovo.at("/data/facets/brands"), "name", "Apple")).isEqualTo(1);
        assertThat(facetCount(lenovo.at("/data/facets/categories"), "name", "Офисные ноутбуки")).isEqualTo(1);

        // Browsing without keyword has facets too
        JsonNode browse = getJson(get("/api/products").param("categoryId", "1"));
        assertThat(browse.at("/data/facets/brands").size()).isPositive();
        assertThat(browse.at("/data/keyword").isNull()).isTrue();
    }

    @Test
    void suggestsProductsCategoriesBrandsAndCompletions() throws Exception {
        JsonNode suggestions = getJson(get("/api/products/suggest").param("q", "смарт"));
        JsonNode data = suggestions.path("data");
        assertThat(data.path("products").size()).isBetween(1, 5);
        assertThat(data.path("products").get(0).has("inStock")).isTrue();
        assertThat(data.path("totalProducts").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(texts(data.path("categories"), "name")).contains("Смартфоны");
        assertThat(data.path("categories").size()).isLessThanOrEqualTo(3);
        assertThat(data.path("brands").size()).isLessThanOrEqualTo(3);
        assertThat(texts(data.path("completions"), null)).contains("смартфон");

        JsonNode layout = getJson(get("/api/products/suggest").param("q", "fqajy")).path("data");
        assertThat(layout.path("correctedQuery").asText()).isEqualTo("айфон");
        assertThat(texts(layout.path("products"), "name")).contains("Смартфон Apple iPhone 15 128 ГБ");

        JsonNode blank = getJson(get("/api/products/suggest").param("q", "   ")).path("data");
        assertThat(blank.path("products").size()).isZero();
        assertThat(blank.path("totalProducts").asInt()).isZero();
    }

    @Test
    void logsCustomerSearchesForStatisticsAndPopularQueries() throws Exception {
        search("колонка");
        search("колонка");
        search("Колонка ");
        search("несуществующийтовар");
        // Found through the keyboard layout correction: counted as "телефон", never suggested as typed
        search("ntktajy");
        // Further pages and administrators are not logged
        getJson(get("/api/products").param("keyword", "колонка").param("page", "1"));
        getJson(get("/api/products").param("keyword", "колонка").header(HttpHeaders.AUTHORIZATION, adminToken()));

        JsonNode stats = getJson(get("/api/search/admin/stats").param("days", "7")
                .header(HttpHeaders.AUTHORIZATION, adminToken())).path("data");
        JsonNode top = find(stats.path("topQueries"), "query", "колонка");
        assertThat(top.path("searches").asInt()).isEqualTo(3);
        assertThat(top.path("averageResults").asDouble()).isGreaterThanOrEqualTo(2.0);
        assertThat(texts(stats.path("zeroResultQueries"), "query")).contains("несуществующийтовар");
        assertThat(stats.path("zeroResultRate").asDouble()).isPositive();

        JsonNode popular = getJson(get("/api/products/search/popular")).path("data");
        assertThat(texts(popular.path("queries"), null)).contains("колонка", "телефон")
                .doesNotContain("несуществующийтовар", "ntktajy");
        assertThat(popular.path("categories").size()).isBetween(1, 6);

        mockMvc.perform(get("/api/search/admin/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/search/admin/stats").header(HttpHeaders.AUTHORIZATION, token("ROLE_USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/admin/stats").param("days", "0")
                        .header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void administratorsManageSynonymsThatTheSearchUsesAtOnce() throws Exception {
        assertThat(search("звучалка").at("/data/totalElements").asInt()).isZero();

        MvcResult created = mockMvc.perform(post("/api/search/admin/synonyms")
                        .header(HttpHeaders.AUTHORIZATION, adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("terms", List.of(" Звучалка ", "marshall", "MARSHALL")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Группа синонимов создана"))
                .andExpect(jsonPath("$.data.terms.length()").value(2))
                .andReturn();
        long groupId = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();
        try {
            assertThat(skus(search("звучалка"))).containsExactly("AUD-MARSHALL-EM2");

            mockMvc.perform(post("/api/search/admin/synonyms")
                            .header(HttpHeaders.AUTHORIZATION, adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("terms", List.of("marshall", "маршал")))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Слово «marshall» уже входит в другую группу синонимов"));
            mockMvc.perform(post("/api/search/admin/synonyms")
                            .header(HttpHeaders.AUTHORIZATION, adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("terms", List.of("один", "ОДИН")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("В группе должно быть не меньше двух разных слов"));
            mockMvc.perform(post("/api/search/admin/synonyms")
                            .header(HttpHeaders.AUTHORIZATION, adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("terms", List.of("plain", "drop;table")))))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(put("/api/search/admin/synonyms/" + groupId)
                            .header(HttpHeaders.AUTHORIZATION, adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("terms", List.of("звучалка", "marshall", "маршал")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Группа синонимов сохранена"))
                    .andExpect(jsonPath("$.data.terms[0]").value("marshall"));

            JsonNode list = getJson(get("/api/search/admin/synonyms").param("keyword", "звуча")
                    .header(HttpHeaders.AUTHORIZATION, adminToken()));
            assertThat(list.at("/data/totalElements").asInt()).isEqualTo(1);
            assertThat(getJson(get("/api/search/admin/synonyms").header(HttpHeaders.AUTHORIZATION, adminToken()))
                    .at("/data/content/0/id").asLong()).isEqualTo(groupId);

            mockMvc.perform(post("/api/search/admin/synonyms")
                            .header(HttpHeaders.AUTHORIZATION, token("ROLE_USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("terms", List.of("x1", "x2")))))
                    .andExpect(status().isForbidden());
        } finally {
            mockMvc.perform(delete("/api/search/admin/synonyms/" + groupId)
                            .header(HttpHeaders.AUTHORIZATION, adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Группа синонимов удалена"));
        }
        mockMvc.perform(delete("/api/search/admin/synonyms/" + groupId).header(HttpHeaders.AUTHORIZATION, adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Группа синонимов не найдена"));
        assertThat(search("звучалка").at("/data/totalElements").asInt()).isZero();
    }

    @Test
    void appliesStockLevelsFromInventoryInOrder() throws Exception {
        Instant now = Instant.now();
        try {
            publishStock(1L, 0, now);
            awaitInStock(1L, false);
            JsonNode found = search("iphone");
            assertThat(found.at("/data/content/0/inStock").asBoolean()).isFalse();

            // An older level, delivered late, does not override the newer one
            publishStock(1L, 7, now.minusSeconds(60));
            publishStock(2L, 0, now);
            awaitInStock(2L, false);
            assertThat(inStock(1L)).isFalse();

            publishStock(1L, 3, now.plusSeconds(1));
            awaitInStock(1L, true);
        } finally {
            jdbcTemplate.update("update products set in_stock = true, stock_updated_at = null where id in (1, 2)");
        }
    }

    private void publishStock(Long productId, int available, Instant at) {
        rabbitTemplate.convertAndSend(MessagingConstants.INVENTORY_EXCHANGE, MessagingConstants.STOCK_LEVEL_CHANGED_KEY,
                new StockLevelChangedEvent(UUID.randomUUID(), at, productId, available));
    }

    private void awaitInStock(Long productId, boolean expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (inStock(productId) != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(inStock(productId)).isEqualTo(expected);
    }

    private boolean inStock(Long productId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select in_stock from products where id = ?", Boolean.class, productId));
    }

    private JsonNode search(String keyword) throws Exception {
        return getJson(get("/api/products").param("keyword", keyword).param("size", "50"));
    }

    private static List<String> skus(JsonNode page) {
        return texts(page.at("/data/content"), "sku");
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(field == null ? node.asText() : node.path(field).asText()));
        return values;
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asText())) {
                return node;
            }
        }
        throw new AssertionError(value + " not found in " + array);
    }

    private static long facetCount(JsonNode facets, String field, String value) {
        return find(facets, field, value).path("count").asLong();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String adminToken() {
        return token("ROLE_ADMIN");
    }

    private String token(String role) {
        JwtProperties properties = new JwtProperties(SECRET, "test-issuer");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(HmacJwtSupport.secretKey(properties)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(JwtClaimNames.EMAIL, "someone@example.com")
                .claim(JwtClaimNames.ROLES, List.of(role))
                .claim(JwtClaimNames.TOKEN_TYPE, JwtClaimNames.ACCESS_TOKEN_TYPE)
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(HmacJwtSupport.ALGORITHM).build(), claims)).getTokenValue();
    }
}
