-- 상품 기준 가용 재고 조회(창고 조건 없이 상품으로 조회)가 인덱스를 쓰도록 product_id를 앞에 둔 인덱스를 추가한다
CREATE INDEX idx_inventory_product ON inventory (product_id, warehouse_id);
