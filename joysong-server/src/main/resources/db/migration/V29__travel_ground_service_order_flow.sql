ALTER TABLE doctor_institution_project_configs
    ADD COLUMN medical_list_price DECIMAL(19,4) NOT NULL DEFAULT 0 AFTER institution_project_id;

ALTER TABLE doctor_project_change_requests
    ADD COLUMN medical_list_price DECIMAL(19,4) NULL AFTER price_suggestion,
    ADD COLUMN current_medical_list_price DECIMAL(19,4) NULL AFTER current_price;

ALTER TABLE orders
    ADD COLUMN payment_flow VARCHAR(40) NOT NULL DEFAULT 'LEGACY_MEDICAL' AFTER status,
    ADD COLUMN medical_list_price_minor BIGINT NULL AFTER total_amount_minor,
    ADD COLUMN platform_service_rate_bps INT NULL AFTER medical_list_price_minor,
    ADD COLUMN travel_ground_service_fee_minor BIGINT NULL AFTER platform_service_rate_bps,
    ADD COLUMN consultant_avatar VARCHAR(500) NULL AFTER consultant_name,
    ADD COLUMN service_activated_at DATETIME NULL AFTER payment_time,
    ADD CONSTRAINT chk_orders_platform_service_rate_bps
        CHECK (platform_service_rate_bps IS NULL OR platform_service_rate_bps BETWEEN 0 AND 10000),
    ADD CONSTRAINT chk_orders_travel_ground_service_fee
        CHECK (travel_ground_service_fee_minor IS NULL OR travel_ground_service_fee_minor > 0);
