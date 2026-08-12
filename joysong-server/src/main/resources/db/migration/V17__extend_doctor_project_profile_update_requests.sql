ALTER TABLE doctor_project_change_requests
    ADD COLUMN consultation_fee DECIMAL(10,2) NULL AFTER price_suggestion,
    ADD COLUMN commission_rate DECIMAL(5,2) NULL AFTER consultation_fee,
    ADD COLUMN institution_rate DECIMAL(5,2) NULL AFTER commission_rate,
    ADD COLUMN base_doctor_project_updated_at TIMESTAMP NULL AFTER institution_rate,
    ADD COLUMN base_config_id VARCHAR(36) NULL AFTER base_doctor_project_updated_at,
    ADD COLUMN base_config_updated_at TIMESTAMP NULL AFTER base_config_id,
    ADD COLUMN force_processed BOOLEAN NOT NULL DEFAULT FALSE AFTER review_note,
    ADD CONSTRAINT chk_dpcr_profile_config CHECK (
        (request_type = 'PROFILE_UPDATE' AND consultation_fee IS NOT NULL AND commission_rate IS NOT NULL AND institution_rate IS NOT NULL)
        OR (request_type <> 'PROFILE_UPDATE' AND consultation_fee IS NULL AND commission_rate IS NULL AND institution_rate IS NULL)
    ),
    ADD CONSTRAINT chk_dpcr_consultation_fee CHECK (consultation_fee IS NULL OR (consultation_fee >= 0 AND consultation_fee <= 99999999.99)),
    ADD CONSTRAINT chk_dpcr_commission_rate CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 100)),
    ADD CONSTRAINT chk_dpcr_institution_rate CHECK (institution_rate IS NULL OR (institution_rate >= 0 AND institution_rate <= 100));
