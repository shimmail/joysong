ALTER TABLE doctor_project_change_requests
    ADD COLUMN current_price DECIMAL(10,2) NULL AFTER base_config_updated_at,
    ADD COLUMN current_service_description TEXT NULL AFTER current_price,
    ADD COLUMN current_service_tags VARCHAR(500) NULL AFTER current_service_description,
    ADD COLUMN current_schedule_note VARCHAR(500) NULL AFTER current_service_tags,
    ADD COLUMN current_cover_image VARCHAR(500) NULL AFTER current_schedule_note,
    ADD COLUMN current_images VARCHAR(2000) NULL AFTER current_cover_image,
    ADD COLUMN current_consultation_fee DECIMAL(10,2) NULL AFTER current_images,
    ADD COLUMN current_commission_rate DECIMAL(5,2) NULL AFTER current_consultation_fee,
    ADD COLUMN current_institution_rate DECIMAL(5,2) NULL AFTER current_commission_rate;
