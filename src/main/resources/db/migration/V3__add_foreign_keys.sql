-- 외래키. 외래키를 뒷받침하는 인덱스는 V2에서 먼저 만든다

ALTER TABLE lot
    ADD CONSTRAINT fk_lot_product
        FOREIGN KEY (product_id) REFERENCES product (product_id);

-- inventory.product_id가 lot의 상품과 항상 일치하도록 (lot_id, product_id) 복합 키로 참조한다
ALTER TABLE inventory
    ADD CONSTRAINT fk_inventory_lot_product
        FOREIGN KEY (lot_id, product_id) REFERENCES lot (lot_id, product_id);

ALTER TABLE inventory_history
    ADD CONSTRAINT fk_history_inventory
        FOREIGN KEY (inventory_id) REFERENCES inventory (inventory_id);

ALTER TABLE reservation_item
    ADD CONSTRAINT fk_reservation_item_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservation (reservation_id),
    ADD CONSTRAINT fk_reservation_item_product
        FOREIGN KEY (product_id) REFERENCES product (product_id);

ALTER TABLE reservation_allocation
    ADD CONSTRAINT fk_allocation_item
        FOREIGN KEY (reservation_item_id) REFERENCES reservation_item (reservation_item_id),
    ADD CONSTRAINT fk_allocation_inventory
        FOREIGN KEY (inventory_id) REFERENCES inventory (inventory_id);

ALTER TABLE reservation_event
    ADD CONSTRAINT fk_reservation_event_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservation (reservation_id);

ALTER TABLE stock_adjustment
    ADD CONSTRAINT fk_adjustment_run
        FOREIGN KEY (reconciliation_run_id) REFERENCES reconciliation_run (reconciliation_run_id);

ALTER TABLE stock_adjustment_item
    ADD CONSTRAINT fk_adjustment_item_adjustment
        FOREIGN KEY (stock_adjustment_id) REFERENCES stock_adjustment (stock_adjustment_id),
    ADD CONSTRAINT fk_adjustment_item_inventory
        FOREIGN KEY (inventory_id) REFERENCES inventory (inventory_id),
    ADD CONSTRAINT fk_adjustment_item_lot
        FOREIGN KEY (lot_id) REFERENCES lot (lot_id);
