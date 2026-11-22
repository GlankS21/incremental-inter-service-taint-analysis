package com.shop.product.repository;

import com.shop.product.dto.response.SearchFacetsResponse;
import com.shop.product.dto.response.SearchFacetsResponse.BrandFacet;
import com.shop.product.dto.response.SearchFacetsResponse.CategoryFacet;
import com.shop.product.dto.response.SearchFacetsResponse.PriceRangeFacet;
import com.shop.product.entity.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full-text and typo-tolerant search of the catalogue in PostgreSQL.
 * <p>
 * A product matches when its {@code search_vector} (Russian stemming + unstemmed SKU and brand, weighted
 * name A, brand and categories B, descriptions C) matches the query, or, when the plan has trigram needles,
 * when a needle is word-similar to its name or brand ({@code pg_trgm}, threshold set per transaction by
 * {@link #useSimilarityThresholds}). The relevance mixes {@code ts_rank_cd} and the trigram similarity with
 * the popularity (sales, rating) and prefers products in stock.
 * <p>
 * The SQL text is assembled from fixed fragments only; every value coming from the request is a bound
 * parameter.
 */
@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    /** Property of the {@code sort} parameter ordering by relevance. */
    public static final String RELEVANCE = "relevance";

    /** Upper bounds (RUB, exclusive) of the price facet ranges; the last range is open. */
    static final List<BigDecimal> PRICE_BOUNDS = List.of(new BigDecimal("5000"), new BigDecimal("15000"),
            new BigDecimal("30000"), new BigDecimal("60000"), new BigDecimal("100000"));

    private static final int FACET_LIMIT = 20;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "price", "p.price",
            "name", "p.name",
            "createdAt", "p.created_at",
            "updatedAt", "p.updated_at",
            "sku", "p.sku",
            "ratingAverage", "p.rating_average",
            "salesCount", "p.sales_count");

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Sets the pg_trgm thresholds for the rest of the current transaction ({@code set_config(..., true)}).
     */
    public void useSimilarityThresholds(double wordSimilarity, double similarity) {
        jdbc.queryForList("select set_config('pg_trgm.word_similarity_threshold', :word, true), "
                        + "set_config('pg_trgm.similarity_threshold', :similarity, true)",
                new MapSqlParameterSource()
                        .addValue("word", Double.toString(wordSimilarity))
                        .addValue("similarity", Double.toString(similarity)));
    }

    public long count(SearchPlan plan, SearchFilters filters) {
        if (plan.isEmpty()) {
            return 0;
        }
        Sql sql = new Sql();
        String text = "select count(*) from products p" + sql.textSource(plan)
                + " where " + sql.match(plan) + sql.filters(filters);
        Long count = jdbc.queryForObject(text, sql.params, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Whether any active product matches the plan by full text alone.
     */
    public boolean anyActiveMatch(SearchPlan plan) {
        if (plan.isEmpty()) {
            return false;
        }
        Sql sql = new Sql();
        String text = "select exists (select 1 from products p" + sql.textSource(plan.withTrigramNeedles(List.of()))
                + " where p.search_vector @@ q.ts" + sql.filters(SearchFilters.publicCatalog()) + ")";
        return Boolean.TRUE.equals(jdbc.queryForObject(text, sql.params, Boolean.class));
    }

    /**
     * Ids of one page of matching products.
     *
     * @param sort {@link #RELEVANCE} or properties of {@link #SORT_COLUMNS}; ties are broken by id (newest
     *             first)
     */
    public List<Long> findIds(SearchPlan plan, SearchFilters filters, Sort sort, int limit, long offset) {
        if (plan.isEmpty()) {
            return List.of();
        }
        Sql sql = new Sql();
        String text = "select p.id from products p" + sql.textSource(plan)
                + " where " + sql.match(plan) + sql.filters(filters)
                + " order by " + sql.orderBy(plan, sort)
                + " limit " + sql.bind(limit) + " offset " + sql.bind(offset);
        return jdbc.queryForList(text, sql.params, Long.class);
    }

    /**
     * Facets of the products matching the plan ({@code null}: every product) and the filters; each facet
     * ignores its own filter.
     */
    public SearchFacetsResponse facets(SearchPlan plan, SearchFilters filters) {
        if (plan != null && plan.isEmpty()) {
            return SearchFacetsResponse.empty();
        }
        PriceSummary prices = priceSummary(plan, filters.withoutPrice());
        return new SearchFacetsResponse(
                categoryFacets(plan, filters.withoutCategories(), FACET_LIMIT),
                brandFacets(plan, filters.withoutBrand(), FACET_LIMIT),
                prices.ranges(), prices.min(), prices.max());
    }

    public List<CategoryFacet> categoryFacets(SearchPlan plan, SearchFilters filters, int limit) {
        Sql sql = new Sql();
        String text = "select c.id, c.name, c.slug, count(*) as matches"
                + " from products p join categories c on c.id = p.category_id" + sql.textSource(plan)
                + " where " + sql.match(plan) + sql.filters(filters)
                + " group by c.id, c.name, c.slug order by matches desc, c.name limit " + sql.bind(limit);
        return jdbc.query(text, sql.params, (rs, row) ->
                new CategoryFacet(rs.getLong("id"), rs.getString("name"), rs.getString("slug"), rs.getLong("matches")));
    }

    /**
     * Brands of the matching products; spellings differing only by case are counted together under the
     * first one in alphabetical order.
     */
    public List<BrandFacet> brandFacets(SearchPlan plan, SearchFilters filters, int limit) {
        Sql sql = new Sql();
        String text = "select min(p.brand) as brand, count(*) as matches from products p" + sql.textSource(plan)
                + " where " + sql.match(plan) + sql.filters(filters) + " and nullif(btrim(p.brand), '') is not null"
                + " group by lower(p.brand) order by matches desc, brand limit " + sql.bind(limit);
        return jdbc.query(text, sql.params, (rs, row) -> new BrandFacet(rs.getString("brand"), rs.getLong("matches")));
    }

    /**
     * Best-selling categories with their number of active products.
     */
    public List<CategoryFacet> popularCategories(int limit) {
        Sql sql = new Sql();
        String text = "select c.id, c.name, c.slug, count(*) as products"
                + " from categories c join products p on p.category_id = c.id"
                + " where p.status = " + sql.bind(ProductStatus.ACTIVE.name())
                + " group by c.id, c.name, c.slug order by sum(p.sales_count) desc, products desc, c.name"
                + " limit " + sql.bind(limit);
        return jdbc.query(text, sql.params, (rs, row) ->
                new CategoryFacet(rs.getLong("id"), rs.getString("name"), rs.getString("slug"), rs.getLong("products")));
    }

    private PriceSummary priceSummary(SearchPlan plan, SearchFilters filters) {
        Sql sql = new Sql();
        StringBuilder text = new StringBuilder("select min(p.price) as min_price, max(p.price) as max_price");
        for (int i = 0; i <= PRICE_BOUNDS.size(); i++) {
            List<String> conditions = new ArrayList<>();
            if (i > 0) {
                conditions.add("p.price >= " + sql.bind(PRICE_BOUNDS.get(i - 1)));
            }
            if (i < PRICE_BOUNDS.size()) {
                conditions.add("p.price < " + sql.bind(PRICE_BOUNDS.get(i)));
            }
            text.append(", count(*) filter (where ").append(String.join(" and ", conditions))
                    .append(") as range_").append(i);
        }
        text.append(" from products p").append(sql.textSource(plan))
                .append(" where ").append(sql.match(plan)).append(sql.filters(filters));
        return jdbc.queryForObject(text.toString(), sql.params, (rs, row) -> {
            List<PriceRangeFacet> ranges = new ArrayList<>();
            for (int i = 0; i <= PRICE_BOUNDS.size(); i++) {
                long count = rs.getLong("range_" + i);
                if (count > 0) {
                    ranges.add(new PriceRangeFacet(i == 0 ? null : PRICE_BOUNDS.get(i - 1),
                            i == PRICE_BOUNDS.size() ? null : PRICE_BOUNDS.get(i), count));
                }
            }
            return new PriceSummary(ranges, rs.getBigDecimal("min_price"), rs.getBigDecimal("max_price"));
        });
    }

    private record PriceSummary(List<PriceRangeFacet> ranges, BigDecimal min, BigDecimal max) {
    }

    /**
     * SQL fragments of one statement and their bound parameters ({@code :v0}, {@code :v1}...).
     */
    private static final class Sql {

        private final MapSqlParameterSource params = new MapSqlParameterSource();
        private int next;

        String bind(Object value) {
            String name = "v" + next++;
            params.addValue(name, value);
            return ":" + name;
        }

        /**
         * Joins the query (computed once) as {@code q.ts}; nothing without a plan.
         */
        String textSource(SearchPlan plan) {
            if (plan == null) {
                return "";
            }
            List<String> slots = new ArrayList<>();
            for (SearchPlan.Slot slot : plan.slots()) {
                List<String> alternatives = new ArrayList<>();
                for (String term : slot.terms()) {
                    String value = bind(term);
                    alternatives.add("(websearch_to_tsquery('shop_russian', " + value + ")"
                            + " || websearch_to_tsquery('shop_simple', " + value + "))");
                }
                if (slot.prefix() != null) {
                    String value = bind(slot.prefix() + ":*");
                    alternatives.add("(to_tsquery('shop_russian', " + value + ")"
                            + " || to_tsquery('shop_simple', " + value + "))");
                }
                slots.add("(" + String.join(" || ", alternatives) + ")");
            }
            String query = String.join(" && ", slots);
            for (String alternative : plan.wholeQueryAlternatives()) {
                String value = bind(alternative);
                query = "(" + query + ") || (websearch_to_tsquery('shop_russian', " + value + ")"
                        + " || websearch_to_tsquery('shop_simple', " + value + "))";
            }
            return " cross join (select " + query + " as ts) q";
        }

        /**
         * Match condition of the plan; always true without a plan.
         */
        String match(SearchPlan plan) {
            if (plan == null) {
                return "true";
            }
            StringBuilder condition = new StringBuilder("(p.search_vector @@ q.ts");
            for (String needle : plan.trigramNeedles()) {
                String value = bind(needle);
                condition.append(" or ").append(value).append(" <% shop_normalize(p.name)")
                        .append(" or ").append(value).append(" <% shop_normalize(p.brand)");
            }
            return condition.append(")").toString();
        }

        /**
         * Requested order; ties are broken by id (newest first).
         */
        String orderBy(SearchPlan plan, Sort sort) {
            List<String> orders = new ArrayList<>();
            for (Sort.Order order : sort.isSorted() ? sort : Sort.by(RELEVANCE)) {
                if (RELEVANCE.equals(order.getProperty())) {
                    orders.add(score(plan) + " desc");
                } else {
                    String column = SORT_COLUMNS.get(order.getProperty());
                    if (column == null) {
                        throw new IllegalArgumentException("Unsupported sort property " + order.getProperty());
                    }
                    orders.add(column + (order.isAscending() ? " asc" : " desc"));
                }
            }
            orders.add("p.id desc");
            return String.join(", ", orders);
        }

        /**
         * Relevance (full-text rank normalised to 0..1, plus half the best trigram similarity) and popularity
         * (logarithm of the sales and of the number of ratings weighted by their average); a product out of
         * stock keeps 60 % of its score.
         */
        String score(SearchPlan plan) {
            return "((ts_rank_cd(p.search_vector, q.ts, 32) + 0.5 * " + similarity(plan) + ")"
                    + " + 0.02 * ln(1 + p.sales_count)"
                    + " + 0.01 * (p.rating_average / 5) * ln(1 + p.rating_count))"
                    + " * (case when p.in_stock then 1.0 else 0.6 end)";
        }

        private String similarity(SearchPlan plan) {
            if (!plan.usesTrigrams()) {
                return "0";
            }
            List<String> similarities = new ArrayList<>();
            for (String needle : plan.trigramNeedles()) {
                String value = bind(needle);
                similarities.add("word_similarity(" + value + ", shop_normalize(p.name))");
                similarities.add("word_similarity(" + value + ", shop_normalize(p.brand))");
            }
            return "greatest(" + String.join(", ", similarities) + ")";
        }

        String filters(SearchFilters filters) {
            StringBuilder condition = new StringBuilder();
            if (filters.status() != null) {
                condition.append(" and p.status = ").append(bind(filters.status().name()));
            }
            if (filters.categoryIds() != null) {
                condition.append(" and p.category_id in (").append(bind(filters.categoryIds())).append(")");
            }
            if (filters.brand() != null) {
                condition.append(" and lower(p.brand) = ").append(bind(filters.brand()));
            }
            if (filters.minPrice() != null) {
                condition.append(" and p.price >= ").append(bind(filters.minPrice()));
            }
            if (filters.maxPrice() != null) {
                condition.append(" and p.price <= ").append(bind(filters.maxPrice()));
            }
            return condition.toString();
        }
    }
}
