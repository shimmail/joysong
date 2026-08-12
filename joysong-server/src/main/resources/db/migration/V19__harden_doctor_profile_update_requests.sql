ALTER TABLE doctor_project_change_requests
    MODIFY COLUMN service_tags TEXT NOT NULL,
    MODIFY COLUMN images TEXT NOT NULL,
    MODIFY COLUMN current_service_tags TEXT NULL,
    MODIFY COLUMN current_images TEXT NULL,
    ADD COLUMN current_platform_rate DECIMAL(5,2) NULL AFTER current_institution_rate,
    ADD COLUMN current_doctor_rate DECIMAL(5,2) NULL AFTER current_platform_rate,
    ADD CONSTRAINT chk_dpcr_profile_current_snapshot CHECK (
        (request_type = 'PROFILE_UPDATE'
            AND current_price IS NOT NULL
            AND current_consultation_fee IS NOT NULL
            AND current_commission_rate IS NOT NULL
            AND current_institution_rate IS NOT NULL
            AND current_platform_rate IS NOT NULL
            AND current_doctor_rate IS NOT NULL)
        OR (request_type <> 'PROFILE_UPDATE'
            AND current_price IS NULL
            AND current_consultation_fee IS NULL
            AND current_commission_rate IS NULL
            AND current_institution_rate IS NULL
            AND current_platform_rate IS NULL
            AND current_doctor_rate IS NULL)
    );
