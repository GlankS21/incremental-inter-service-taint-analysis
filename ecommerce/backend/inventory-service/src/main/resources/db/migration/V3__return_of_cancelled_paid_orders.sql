-- A paid order can now be cancelled until it is shipped: its sold units come back to the stock.
ALTER TABLE stock_reservations DROP CONSTRAINT ck_stock_reservations_status;
ALTER TABLE stock_reservations ADD CONSTRAINT ck_stock_reservations_status
    CHECK (status IN ('RESERVED', 'FAILED', 'COMMITTED', 'RELEASED', 'RETURNED'));

ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movements_type;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_type
    CHECK (type IN ('ADJUSTMENT', 'RESERVATION', 'RELEASE', 'SALE', 'RETURN'));
