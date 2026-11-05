-- Smart search: Russian full-text search with weights, typo tolerance (trigrams), synonyms managed by
-- administrators, a dictionary of catalogue words for spelling corrections and completions, a search
-- log for statistics, and the popularity signals used by the ranking (sales, stock).

-- Both extensions are "trusted": the database owner (product_user) may create them
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Text search configurations: the built-in "russian" and "simple" with accents removed first
-- ("ё" -> "е", "café" -> "cafe"). Latin words keep the English stemmer of "russian".
CREATE TEXT SEARCH CONFIGURATION shop_russian (COPY = russian);
ALTER TEXT SEARCH CONFIGURATION shop_russian
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, russian_stem;

CREATE TEXT SEARCH CONFIGURATION shop_simple (COPY = simple);
ALTER TEXT SEARCH CONFIGURATION shop_simple
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;

-- Lower case without accents; IMMUTABLE (unlike unaccent itself) so that it can back indexes
CREATE FUNCTION shop_normalize(value TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    RETURN lower(public.unaccent('public.unaccent'::regdictionary, coalesce(value, '')));

-- ---------------------------------------------------------------------------------------------------
-- Products: search document and ranking signals
-- ---------------------------------------------------------------------------------------------------

ALTER TABLE products
    ADD COLUMN search_vector    TSVECTOR    NOT NULL DEFAULT ''::tsvector,
    -- Synced from inventory-service (stock.level-changed); a new product is out of stock until counted
    ADD COLUMN in_stock         BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN stock_updated_at TIMESTAMPTZ,
    -- Delivered orders containing the product (one per order, see product_purchases)
    ADD COLUMN sales_count      INT         NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_products_sales_count CHECK (sales_count >= 0);

-- Weights: name and SKU (A), brand and category names (B), descriptions (C). The SKU and the brand are
-- also indexed without stemming ("simple") so that codes and brand names match as typed.
CREATE FUNCTION product_search_vector(p_name TEXT, p_sku TEXT, p_brand TEXT, p_short_description TEXT,
                                      p_description TEXT, p_category_id BIGINT) RETURNS TSVECTOR
    LANGUAGE sql
    STABLE
AS
$$
SELECT setweight(to_tsvector('shop_russian', coalesce(p_name, '')), 'A')
           || setweight(to_tsvector('shop_simple', coalesce(p_sku, '')), 'A')
           || setweight(to_tsvector('shop_simple', coalesce(p_brand, '')), 'B')
           || setweight(to_tsvector('shop_russian', coalesce(p_brand, '')), 'B')
           || setweight(to_tsvector('shop_russian', coalesce((SELECT concat_ws(' ', c.name, parent.name)
                                                              FROM categories c
                                                                       LEFT JOIN categories parent ON parent.id = c.parent_id
                                                              WHERE c.id = p_category_id), '')), 'B')
           || setweight(to_tsvector('shop_russian', coalesce(p_short_description, '')), 'C')
           || setweight(to_tsvector('shop_russian', coalesce(p_description, '')), 'C')
$$;

CREATE FUNCTION products_refresh_search_vector() RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    NEW.search_vector := product_search_vector(NEW.name, NEW.sku, NEW.brand, NEW.short_description,
                                               NEW.description, NEW.category_id);
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_products_search_vector
    BEFORE INSERT OR UPDATE OF name, sku, brand, short_description, description, category_id
    ON products
    FOR EACH ROW
EXECUTE FUNCTION products_refresh_search_vector();

-- A renamed (or moved) category changes the documents of its products and of its sub-categories' products
CREATE FUNCTION categories_refresh_product_search_vectors() RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    UPDATE products p
    SET search_vector = product_search_vector(p.name, p.sku, p.brand, p.short_description, p.description,
                                              p.category_id)
    WHERE p.category_id = NEW.id
       OR p.category_id IN (SELECT c.id FROM categories c WHERE c.parent_id = NEW.id);
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_categories_product_search_vectors
    AFTER UPDATE OF name, parent_id
    ON categories
    FOR EACH ROW
    WHEN (OLD.name IS DISTINCT FROM NEW.name OR OLD.parent_id IS DISTINCT FROM NEW.parent_id)
EXECUTE FUNCTION categories_refresh_product_search_vectors();

UPDATE products p
SET search_vector = product_search_vector(p.name, p.sku, p.brand, p.short_description, p.description,
                                          p.category_id),
    sales_count   = (SELECT count(*) FROM product_purchases pp WHERE pp.product_id = p.id),
    -- Existing products are assumed available until inventory-service publishes their real level
    in_stock      = TRUE;

CREATE INDEX idx_products_search_vector ON products USING gin (search_vector);
CREATE INDEX idx_products_name_trgm ON products USING gin (shop_normalize(name) gin_trgm_ops);
CREATE INDEX idx_products_brand_trgm ON products USING gin (shop_normalize(brand) gin_trgm_ops);
CREATE INDEX idx_products_sales_count ON products (sales_count DESC);

-- ---------------------------------------------------------------------------------------------------
-- Synonyms: groups of interchangeable terms, a term belongs to at most one group
-- ---------------------------------------------------------------------------------------------------

CREATE TABLE search_synonyms
(
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE search_synonym_terms
(
    synonym_id BIGINT      NOT NULL,
    term       VARCHAR(60) NOT NULL,
    CONSTRAINT pk_search_synonym_terms PRIMARY KEY (synonym_id, term),
    CONSTRAINT uk_search_synonym_terms_term UNIQUE (term),
    CONSTRAINT fk_search_synonym_terms_synonym FOREIGN KEY (synonym_id) REFERENCES search_synonyms (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_search_synonym_terms_normalized CHECK (term = lower(btrim(term)) AND term <> '')
);

WITH seed(position, terms) AS (
    VALUES (1, ARRAY ['смартфон', 'телефон', 'мобильный', 'мобильник', 'smartphone', 'phone']),
           (2, ARRAY ['ноутбук', 'лэптоп', 'лептоп', 'laptop', 'notebook']),
           (3, ARRAY ['айфон', 'iphone']),
           (4, ARRAY ['айпад', 'ipad']),
           (5, ARRAY ['макбук', 'macbook']),
           (6, ARRAY ['наушники', 'гарнитура', 'headphones', 'earbuds']),
           (7, ARRAY ['планшет', 'tablet']),
           (8, ARRAY ['колонка', 'акустика', 'speaker']),
           (9, ARRAY ['зарядка', 'зарядное', 'charger', 'адаптер']),
           (10, ARRAY ['мышь', 'мышка', 'mouse']),
           (11, ARRAY ['клавиатура', 'keyboard']),
           (12, ARRAY ['кабель', 'провод', 'шнур', 'cable']),
           (13, ARRAY ['самсунг', 'samsung']),
           (14, ARRAY ['сяоми', 'ксиаоми', 'xiaomi']),
           (15, ARRAY ['эпл', 'эппл', 'apple']),
           (16, ARRAY ['гугл', 'google']),
           (17, ARRAY ['леново', 'lenovo']),
           (18, ARRAY ['асус', 'asus']),
           (19, ARRAY ['сони', 'sony'])),
     groups AS (
         INSERT INTO search_synonyms (created_at, updated_at)
             SELECT now(), now() FROM seed ORDER BY position
             RETURNING id),
     numbered_groups AS (SELECT id, row_number() OVER (ORDER BY id) AS position FROM groups)
INSERT
INTO search_synonym_terms (synonym_id, term)
SELECT g.id, t.term
FROM seed s
         JOIN numbered_groups g ON g.position = s.position
         CROSS JOIN LATERAL unnest(s.terms) AS t(term);

-- ---------------------------------------------------------------------------------------------------
-- Search log: one row per catalogue search (first page), for popular queries and statistics
-- ---------------------------------------------------------------------------------------------------

CREATE TABLE search_queries
(
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    -- Normalised: trimmed, lower case, single spaces
    query           VARCHAR(100) NOT NULL,
    corrected_query VARCHAR(100),
    results_count   INT          NOT NULL,
    searched_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_search_queries_results_count CHECK (results_count >= 0)
);

CREATE INDEX idx_search_queries_searched_at ON search_queries (searched_at);
CREATE INDEX idx_search_queries_query_prefix ON search_queries (query text_pattern_ops, searched_at);

-- ---------------------------------------------------------------------------------------------------
-- Dictionary of the words of the public catalogue (product and brand names, category names): spelling
-- corrections and completions. Refreshed by the service after catalogue changes.
-- ---------------------------------------------------------------------------------------------------

CREATE MATERIALIZED VIEW search_words AS
SELECT word, count(*)::INT AS frequency
FROM (SELECT regexp_split_to_table(shop_normalize(concat_ws(' ', p.name, p.brand)), '[^[:alnum:]]+') AS word
      FROM products p
      WHERE p.status = 'ACTIVE'
      UNION ALL
      SELECT regexp_split_to_table(shop_normalize(c.name), '[^[:alnum:]]+')
      FROM categories c) words
WHERE length(word) >= 2
GROUP BY word;

CREATE UNIQUE INDEX uk_search_words_word ON search_words (word);
CREATE INDEX idx_search_words_trgm ON search_words USING gin (word gin_trgm_ops);
CREATE INDEX idx_search_words_prefix ON search_words (word text_pattern_ops);
