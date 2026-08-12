ALTER TABLE doctor_project_change_requests
    MODIFY COLUMN service_tags TEXT NOT NULL,
    MODIFY COLUMN images TEXT NOT NULL,
    MODIFY COLUMN current_service_tags TEXT NULL,
    MODIFY COLUMN current_images TEXT NULL,
    ADD CONSTRAINT chk_dpcr_profile_config CHECK (
        (request_type = 'PROFILE_UPDATE' AND consultation_fee IS NOT NULL AND commission_rate IS NOT NULL AND institution_rate IS NOT NULL)
        OR (request_type <> 'PROFILE_UPDATE' AND consultation_fee IS NULL AND commission_rate IS NULL AND institution_rate IS NULL)
    ),
    ADD CONSTRAINT chk_dpcr_consultation_fee CHECK (consultation_fee IS NULL OR (consultation_fee >= 0 AND consultation_fee <= 99999999.99)),
    ADD CONSTRAINT chk_dpcr_commission_rate CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 100)),
    ADD CONSTRAINT chk_dpcr_institution_rate CHECK (institution_rate IS NULL OR (institution_rate >= 0 AND institution_rate <= 100)),
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
