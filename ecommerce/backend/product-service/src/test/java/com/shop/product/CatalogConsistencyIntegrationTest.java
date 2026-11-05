package com.shop.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BusinessException;
import com.shop.product.dto.request.CategoryRequest;
import com.shop.product.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stable paging over products that tie on the sort columns, and the two-level category tree under
 * concurrent re-parenting. Has its own containers because it adds ACTIVE products and categories,
 * which would change the counts of the other catalogue tests.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.security.jwt.secret=" + CatalogConsistencyIntegrationTest.SECRET,
        "app.security.jwt.issuer=test-issuer",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CatalogConsistencyIntegrationTest {

    static final String SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIz";
    private static final int TIED_PRODUCTS = 30;
    private static final int PAGE_SIZE = 10;

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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void pagesOfTiedProductsNeitherRepeatNorSkipProducts() throws Exception {
        Long categoryId = jdbcTemplate.queryForObject("""
                insert into categories (name, slug, sort_order, created_at, updated_at)
                values ('Одинаковые товары', 'odinakovye-tovary', 0, now(), now()) returning id
                """, Long.class);
        Timestamp createdAt = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        Set<Long> inserted = new HashSet<>();
        for (int i = 0; i < TIED_PRODUCTS; i++) {
            inserted.add(jdbcTemplate.queryForObject("""
                    insert into products (sku, name, slug, brand, price, category_id, status, created_at, updated_at)
                    values (?, ?, ?, 'Tie', 1990, ?, 'ACTIVE', ?, ?) returning id
                    """, Long.class, "TIE-" + i, "Одинаковый товар " + i, "odinakovyi-tovar-" + i, categoryId,
                    createdAt, createdAt));
        }

        for (String sort : new String[]{null, "relevance", "createdAt,asc", "price,asc", "ratingAverage,desc"}) {
            List<Long> seen = new ArrayList<>();
            for (int page = 0; page < TIED_PRODUCTS / PAGE_SIZE; page++) {
                MockHttpServletRequestBuilder request = get("/api/products")
                        .param("categoryId", categoryId.toString())
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(PAGE_SIZE));
                if (sort != null) {
                    request.param("sort", sort);
                }
                List<Long> pageIds = new ArrayList<>();
                getJson(request).at("/data/content").forEach(product -> pageIds.add(product.get("id").asLong()));
                seen.addAll(pageIds);
                // Stock events rewrite product rows between two page requests, which moves tied rows
                // around in the table
                if (!pageIds.isEmpty()) {
                    jdbcTemplate.update("update products set in_stock = not in_stock, stock_updated_at = now() "
                            + "where id in (" + String.join(",", pageIds.stream().map(String::valueOf).toList()) + ")");
                }
            }
            assertThat(seen).as("ids of all pages, sort=%s", sort)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(inserted);
        }
    }

    @Test
    void concurrentReparentingNeverBuildsAThirdLevel() throws Exception {
        Long child = categoryService.createCategory(root("Будущая подкатегория", "budushchaia-podkategoriia")).id();
        Long parent = categoryService.createCategory(root("Будущий родитель", "budushchii-roditel")).id();
        Long grandParent = categoryService.createCategory(root("Корень", "koren")).id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch childMoved = new CountDownLatch(1);
            CountDownLatch commitChildMove = new CountDownLatch(1);
            // Moves the child under the parent and keeps its transaction open until released
            Future<?> moveChild = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        categoryService.updateCategory(child, moveTo("Будущая подкатегория", parent));
                        childMoved.countDown();
                        await(commitChildMove);
                    }));
            assertThat(childMoved.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> moveParent = executor.submit(
                    () -> categoryService.updateCategory(parent, moveTo("Будущий родитель", grandParent)));
            try {
                moveParent.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException blockedUntilTheChildMoveCommits) {
                // expected once the categories are locked
            } catch (ExecutionException ignored) {
                // checked below
            }
            commitChildMove.countDown();
            moveChild.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> moveParent.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("category.error.parent-has-children");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from categories c join categories p on p.id = c.parent_id
                where p.parent_id is not null
                """, Long.class)).isZero();
    }

    private static CategoryRequest root(String name, String slug) {
        return new CategoryRequest(name, slug, null, null, null);
    }

    private static CategoryRequest moveTo(String name, Long parentId) {
        return new CategoryRequest(name, null, null, parentId, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
