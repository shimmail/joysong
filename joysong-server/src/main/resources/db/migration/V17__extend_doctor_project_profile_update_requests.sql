ALTER TABLE doctor_project_change_requests
    ADD COLUMN consultation_fee DECIMAL(10,2) NULL AFTER price_suggestion,
    ADD COLUMN commission_rate DECIMAL(5,2) NULL AFTER consultation_fee,
    ADD COLUMN institution_rate DECIMAL(5,2) NULL AFTER commission_rate,
    ADD COLUMN base_doctor_project_updated_at TIMESTAMP NULL AFTER institution_rate,
    ADD COLUMN base_config_id VARCHAR(36) NULL AFTER base_doctor_project_updated_at,
    ADD COLUMN base_config_updated_at TIMESTAMP NULL AFTER base_config_id,
    ADD COLUMN force_processed BOOLEAN NOT NULL DEFAULT FALSE AFTER review_note;
