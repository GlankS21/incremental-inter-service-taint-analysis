-- The shop now sells in roubles (CATALOG_CURRENCY=RUB, online payments through YooKassa accept RUB only).
-- Prices of the catalogue are converted at about 300 VND per rouble and rounded up to a price ending
-- in 90 roubles, e.g. 19 990 000 VND -> 66 690 RUB. A crossed-out price that would no longer be higher
-- than the price after rounding is dropped.
UPDATE products
SET price            = CEIL(price / 30000) * 100 - 10,
    compare_at_price = CASE
                           WHEN CEIL(compare_at_price / 30000) * 100 - 10 > CEIL(price / 30000) * 100 - 10
                               THEN CEIL(compare_at_price / 30000) * 100 - 10
        END;
