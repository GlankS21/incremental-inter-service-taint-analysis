-- product-service now keeps an "in stock" flag from stock.level-changed events. The current level of
-- every product is queued once in the outbox, in the format OutboxService writes, so that the relay
-- publishes it at startup and product-service starts from the real stock.
-- The event id is generated in the select list of the CTE, i.e. once per row (an uncorrelated subquery
-- would be evaluated only once and give every row the same id).
WITH levels AS (SELECT gen_random_uuid()                         AS event_id,
                       product_id,
                       quantity_on_hand - quantity_reserved      AS available
                FROM inventory_items)
INSERT INTO outbox_events (id, aggregate_id, exchange, routing_key, event_type, payload, created_at, attempts)
SELECT event_id,
       product_id::text,
       'shop.inventory.events',
       'stock.level-changed',
       'com.shop.common.event.StockLevelChangedEvent',
       json_build_object('eventId', event_id,
                         'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                         'productId', product_id,
                         'availableQuantity', available)::text,
       now(),
       0
FROM levels;
