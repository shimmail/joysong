ALTER TABLE professional_project_requests
    DROP CHECK chk_project_requests_shape,
    DROP CHECK chk_project_requests_price,
    DROP CHECK chk_project_requests_review_note,
    DROP COLUMN service_content,
    CHANGE COLUMN price_suggestion price DECIMAL(10,2) NULL,
    ADD COLUMN tags JSON DEFAULT (JSON_ARRAY()) AFTER description,
    ADD COLUMN slogan VARCHAR(500) NOT NULL DEFAULT '' AFTER tags,
    ADD COLUMN detail_content TEXT NULL AFTER slogan,
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER detail_content,
    ADD COLUMN cover_image VARCHAR(500) NOT NULL DEFAULT '' AFTER currency,
    ADD COLUMN images JSON DEFAULT (JSON_ARRAY()) AFTER cover_image,
    ADD COLUMN sales_count INT NOT NULL DEFAULT 0 AFTER images,
    ADD COLUMN reference_price DECIMAL(10,2) NULL DEFAULT 0.00 AFTER sales_count,
    ADD COLUMN category_tags JSON DEFAULT (JSON_ARRAY()) AFTER reference_price,
    ADD COLUMN original_price DECIMAL(10,2) NULL AFTER price,
    ADD COLUMN is_active TINYINT(1) NULL AFTER original_price,
    ADD COLUMN consultation_fee DECIMAL(10,2) NULL AFTER is_active,
    ADD COLUMN commission_rate DECIMAL(5,2) NULL AFTER consultation_fee,
    ADD COLUMN institution_rate DECIMAL(5,2) NULL AFTER commission_rate,
    ADD CONSTRAINT chk_project_requests_amounts CHECK (
        (price IS NULL OR price >= 0)
        AND (original_price IS NULL OR original_price >= 0)
        AND (reference_price IS NULL OR reference_price >= 0)
        AND (consultation_fee IS NULL OR consultation_fee >= 0)
    ),
    ADD CONSTRAINT chk_project_requests_sales_count CHECK (sales_count >= 0),
    ADD CONSTRAINT chk_project_requests_currency CHECK (currency IN ('USD', 'CNY')),
    ADD CONSTRAINT chk_project_requests_is_active CHECK (is_active IS NULL OR is_active IN (0, 1)),
    ADD CONSTRAINT chk_project_requests_rates CHECK (
        (commission_rate IS NULL OR commission_rate BETWEEN 0 AND 100)
        AND (institution_rate IS NULL OR institution_rate BETWEEN 0 AND 100)
        AND (
            commission_rate IS NULL
            OR institution_rate IS NULL
            OR commission_rate + institution_rate <= 100
        )
    ),
    ADD CONSTRAINT chk_project_requests_review_note CHECK (
        status <> 'REJECTED' OR (review_note IS NOT NULL AND TRIM(review_note) <> '')
    ),
    ADD CONSTRAINT chk_project_requests_shape CHECK (
        (
            request_type = 'PLATFORM'
            AND institution_id IS NULL
            AND project_id IS NULL
            AND name IS NOT NULL
            AND category IS NOT NULL
            AND description IS NOT NULL
            AND reference_price IS NOT NULL
            AND price IS NULL
            AND original_price IS NULL
            AND is_active IS NULL
            AND consultation_fee IS NULL
            AND commission_rate IS NULL
            AND institution_rate IS NULL
        )
        OR
        (
            request_type = 'INSTITUTION'
            AND institution_id IS NOT NULL
            AND project_id IS NOT NULL
            AND price IS NOT NULL
            AND is_active IS NOT NULL
            AND consultation_fee IS NOT NULL
            AND commission_rate IS NOT NULL
            AND institution_rate IS NOT NULL
            AND reference_price IS NULL
            AND category_tags IS NULL
        )
    );
