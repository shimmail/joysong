ALTER TABLE doctor_projects
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD INDEX idx_doctor_projects_public_lookup (institution_project_id, is_active, price);

ALTER TABLE institution_projects
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    MODIFY cover_image VARCHAR(500) NULL,
    MODIFY images VARCHAR(2000) NULL;

ALTER TABLE doctor_project_change_requests
    ADD COLUMN payload_version INT NOT NULL DEFAULT 1,
    ADD COLUMN base_institution_project_version BIGINT NULL,
    ADD COLUMN base_platform_inheritance_hash CHAR(64) NULL,
    ADD COLUMN pricing_policy_revision VARCHAR(100) NULL,
    ADD COLUMN proposed_travel_ground_service_fee DECIMAL(18,2) NULL,
    ADD COLUMN shared_changed BOOLEAN NULL,
    ADD COLUMN current_project_snapshot JSON NULL,
    ADD COLUMN proposed_project_snapshot JSON NULL,
    ADD COLUMN current_doctor_is_active BOOLEAN NULL,
    ADD COLUMN proposed_doctor_is_active BOOLEAN NULL,
    ADD COLUMN approval_audit_snapshot JSON NULL,
    DROP CHECK chk_dpcr_profile_config,
    DROP CHECK chk_dpcr_profile_current_snapshot,
    MODIFY service_tags TEXT NULL,
    MODIFY images TEXT NULL,
    ADD CONSTRAINT chk_dpcr_payload_version CHECK (payload_version IN (1, 2)),
    ADD CONSTRAINT chk_dpcr_v1_legacy_shape CHECK (
        payload_version <> 1
        OR (
            service_tags IS NOT NULL
            AND images IS NOT NULL
            AND (
                (request_type = 'PROFILE_UPDATE'
                    AND consultation_fee IS NOT NULL
                    AND commission_rate IS NOT NULL
                    AND institution_rate IS NOT NULL
                    AND current_price IS NOT NULL
                    AND current_consultation_fee IS NOT NULL
                    AND current_commission_rate IS NOT NULL
                    AND current_institution_rate IS NOT NULL
                    AND current_platform_rate IS NOT NULL
                    AND current_doctor_rate IS NOT NULL)
                OR
                (request_type <> 'PROFILE_UPDATE'
                    AND consultation_fee IS NULL
                    AND commission_rate IS NULL
                    AND institution_rate IS NULL
                    AND current_price IS NULL
                    AND current_consultation_fee IS NULL
                    AND current_commission_rate IS NULL
                    AND current_institution_rate IS NULL
                    AND current_platform_rate IS NULL
                    AND current_doctor_rate IS NULL)
            )
        )
    ),
    ADD CONSTRAINT chk_dpcr_v2_versioned_shape CHECK (
        payload_version <> 2
        OR (
            price_suggestion IS NULL
            AND medical_list_price IS NOT NULL
            AND medical_list_price >= 0
            AND current_price IS NOT NULL
            AND base_institution_project_version IS NOT NULL
            AND base_platform_inheritance_hash IS NOT NULL
            AND CHAR_LENGTH(base_platform_inheritance_hash) = 64
            AND pricing_policy_revision IS NOT NULL
            AND shared_changed IS NOT NULL
            AND current_project_snapshot IS NOT NULL
            AND proposed_project_snapshot IS NOT NULL
            AND current_doctor_is_active IS NOT NULL
            AND proposed_doctor_is_active IS NOT NULL
        )
    );
