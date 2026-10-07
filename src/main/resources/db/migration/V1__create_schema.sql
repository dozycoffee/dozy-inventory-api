-- 테이블, 유니크, CHECK 제약. 인덱스는 V2, 외래키는 V3에 둔다

CREATE TABLE product
(
    product_id BIGINT NOT NULL AUTO_INCREMENT,
    product_code VARCHAR(50) NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    shelf_life_days INT DEFAULT NULL,
    product_status VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    deleted_by VARCHAR(100) DEFAULT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT uq_product_code UNIQUE (product_code),
    CONSTRAINT chk_product_shelf_life_days CHECK ((shelf_life_days IS NULL) OR (shelf_life_days >= 0)),
    CONSTRAINT ck_product_category CHECK (category IN ('BEAN', 'SYRUP', 'POWDER', 'DAIRY', 'SUPPLY', 'MD')),
    CONSTRAINT ck_product_status CHECK (product_status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE lot
(
    lot_id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    lot_number VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    manufacture_date DATE DEFAULT NULL,
    expiration_date DATE DEFAULT NULL,
    lot_status VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (lot_id),
    CONSTRAINT uq_lot_product_number UNIQUE (product_id, lot_number),
    CONSTRAINT uq_lot_id_product UNIQUE (lot_id, product_id),
    CONSTRAINT chk_lot_expiration_date CHECK ((manufacture_date IS NULL) OR (expiration_date IS NULL) OR (expiration_date >= manufacture_date)),
    CONSTRAINT ck_lot_status CHECK (lot_status IN ('NORMAL', 'EXPIRING_SOON', 'EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE inventory
(
    inventory_id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    lot_id BIGINT NOT NULL,
    quality_status VARCHAR(50) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0,
    allocation_hold TINYINT(1) NOT NULL DEFAULT 0,
    hold_reason VARCHAR(100) DEFAULT NULL,
    held_at DATETIME(6) DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (inventory_id),
    CONSTRAINT uq_inventory_row UNIQUE (warehouse_id, lot_id, quality_status),
    CONSTRAINT chk_inventory_quantity CHECK (quantity >= 0),
    CONSTRAINT chk_inventory_reserved CHECK ((reserved_quantity >= 0) AND (reserved_quantity <= quantity)),
    CONSTRAINT chk_inventory_hold CHECK ((allocation_hold = 0) OR (held_at IS NOT NULL)),
    CONSTRAINT ck_inventory_quality_status CHECK (quality_status IN ('NORMAL', 'DEFECTIVE', 'DISPOSAL_SCHEDULED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE inventory_history
(
    inventory_history_id BIGINT NOT NULL AUTO_INCREMENT,
    inventory_id BIGINT NOT NULL,
    history_type VARCHAR(50) NOT NULL,
    quantity_change INT NOT NULL,
    quantity_after INT NOT NULL,
    reference_type VARCHAR(50) NOT NULL,
    reference_id BIGINT NOT NULL,
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_service VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (inventory_history_id),
    CONSTRAINT uq_history_idempotency UNIQUE (idempotency_key, inventory_id),
    CONSTRAINT chk_history_change CHECK (quantity_change <> 0),
    CONSTRAINT chk_history_after CHECK (quantity_after >= 0),
    CONSTRAINT ck_history_type CHECK (history_type IN
                                      ('INBOUND', 'RETURN', 'OUTBOUND', 'DISPOSAL', 'ADJUSTMENT', 'RECONCILIATION', 'QUALITY_TRANSFER')),
    CONSTRAINT ck_history_reference_type CHECK (reference_type IN
                                                ('INBOUND_ITEM', 'RETURN_ITEM', 'RESERVATION', 'DISPOSAL_ITEM', 'STOCK_ADJUSTMENT_ITEM',
                                                 'LOT_EXPIRATION'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation
(
    reservation_id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    channel VARCHAR(50) NOT NULL,
    external_order_id VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    expires_at DATETIME(6) DEFAULT NULL,
    max_expires_at DATETIME(6) NOT NULL,
    confirmed_at DATETIME(6) DEFAULT NULL,
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_service VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT uq_reservation_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_reservation_expiry CHECK ((status <> 'RESERVED') OR ((expires_at IS NOT NULL) AND (expires_at <= max_expires_at))),
    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'FULFILLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_item
(
    reservation_item_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    requested_quantity INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_item_id),
    CONSTRAINT uq_reservation_item UNIQUE (reservation_id, product_id),
    CONSTRAINT chk_reservation_item_quantity CHECK (requested_quantity > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_allocation
(
    reservation_allocation_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_item_id BIGINT NOT NULL,
    inventory_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    fulfilled_quantity INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_allocation_id),
    CONSTRAINT uq_allocation UNIQUE (reservation_item_id, inventory_id),
    CONSTRAINT chk_allocation_quantity CHECK ((quantity >= 0) AND (fulfilled_quantity >= 0) AND (fulfilled_quantity <= quantity))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_event
(
    reservation_event_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    detail JSON DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_event_id),
    CONSTRAINT ck_reservation_event_type CHECK (event_type IN
                                                ('CREATED', 'EXTENDED', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'FORCE_RELEASED', 'REALLOCATED',
                                                 'FULFILLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE reconciliation_run
(
    reconciliation_run_id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) DEFAULT NULL,
    checked_count INT NOT NULL DEFAULT 0,
    mismatch_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (reconciliation_run_id),
    CONSTRAINT chk_reconciliation_run_count CHECK ((checked_count >= 0) AND (mismatch_count >= 0)),
    CONSTRAINT ck_reconciliation_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_adjustment
(
    stock_adjustment_id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    adjustment_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    external_reference_id BIGINT DEFAULT NULL,
    reconciliation_run_id BIGINT DEFAULT NULL,
    requested_by VARCHAR(100) NOT NULL,
    approved_by VARCHAR(100) DEFAULT NULL,
    approved_at DATETIME(6) DEFAULT NULL,
    idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (stock_adjustment_id),
    CONSTRAINT uq_adjustment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_adjustment_approval CHECK
        ((adjustment_type = 'AUDIT') OR (status NOT IN ('APPROVED', 'APPLIED')) OR (approved_by IS NOT NULL)),
    CONSTRAINT ck_adjustment_type CHECK (adjustment_type IN ('AUDIT', 'RECONCILIATION')),
    CONSTRAINT ck_adjustment_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'APPLIED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_adjustment_item
(
    stock_adjustment_item_id BIGINT NOT NULL AUTO_INCREMENT,
    stock_adjustment_id BIGINT NOT NULL,
    inventory_id BIGINT DEFAULT NULL,
    lot_id BIGINT NOT NULL,
    quality_status VARCHAR(50) NOT NULL,
    quantity_change INT NOT NULL,
    inventory_quantity INT DEFAULT NULL,
    wms_quantity INT DEFAULT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (stock_adjustment_item_id),
    CONSTRAINT uq_adjustment_item UNIQUE (stock_adjustment_id, lot_id, quality_status),
    CONSTRAINT chk_adjustment_item_change CHECK (quantity_change <> 0),
    CONSTRAINT ck_adjustment_item_quality_status CHECK (quality_status IN ('NORMAL', 'DEFECTIVE', 'DISPOSAL_SCHEDULED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_event
(
    outbox_event_id BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(50) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (outbox_event_id),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('INVENTORY', 'RESERVATION', 'LOT', 'PRODUCT', 'ADJUSTMENT')),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE warehouse_access
(
    warehouse_access_id BIGINT NOT NULL AUTO_INCREMENT,
    principal_id VARCHAR(36) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (warehouse_access_id),
    CONSTRAINT uq_warehouse_access UNIQUE (principal_id, warehouse_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
