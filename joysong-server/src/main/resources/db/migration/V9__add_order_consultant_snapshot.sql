ALTER TABLE orders
    ADD COLUMN consultant_id VARCHAR(36) NOT NULL DEFAULT '' AFTER institution_id,
    ADD COLUMN consultant_name VARCHAR(100) NOT NULL DEFAULT '' AFTER consultant_id,
    ADD KEY idx_orders_consultant_id (consultant_id);
