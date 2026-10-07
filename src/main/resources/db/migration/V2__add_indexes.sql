-- 인덱스. 테이블(V1)을 만든 뒤, 외래키(V3)보다 먼저 만들어 MySQL이 외래키용 인덱스를 자동으로 만들지 않게 한다

-- 외래키를 뒷받침하는 인덱스(V3의 외래키가 쓴다)
CREATE INDEX idx_inventory_lot_product ON inventory (lot_id, product_id);
CREATE INDEX idx_reservation_item_product ON reservation_item (product_id);
CREATE INDEX idx_allocation_inventory ON reservation_allocation (inventory_id);
CREATE INDEX idx_adjustment_run ON stock_adjustment (reconciliation_run_id);
CREATE INDEX idx_adjustment_item_lot ON stock_adjustment_item (lot_id);
CREATE INDEX idx_adjustment_item_inventory ON stock_adjustment_item (inventory_id);

-- 조회와 스캔 성능용 인덱스
CREATE INDEX idx_lot_expiration_date ON lot (expiration_date);
CREATE INDEX idx_inventory_available ON inventory (warehouse_id, product_id, quality_status);
CREATE INDEX idx_history_inventory_created ON inventory_history (inventory_id, created_at);
CREATE INDEX idx_history_reference ON inventory_history (reference_type, reference_id);
CREATE INDEX idx_reservation_expiry ON reservation (status, expires_at);
CREATE INDEX idx_reservation_order ON reservation (channel, external_order_id);
CREATE INDEX idx_reservation_event ON reservation_event (reservation_id, created_at);
CREATE INDEX idx_reconciliation_run ON reconciliation_run (warehouse_id, started_at);
CREATE INDEX idx_outbox_pending ON outbox_event (status, outbox_event_id);
CREATE INDEX idx_inventory_product ON inventory (product_id, warehouse_id);  -- 창고 없이 상품으로 조회하는 가용 재고 조회
