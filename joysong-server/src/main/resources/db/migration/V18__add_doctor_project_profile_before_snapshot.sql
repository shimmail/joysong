ALTER TABLE doctor_project_change_requests
    ADD COLUMN current_price DECIMAL(10,2) NULL AFTER base_config_updated_at,
    ADD COLUMN current_service_description TEXT NULL AFTER current_price,
    ADD COLUMN current_service_tags VARCHAR(500) NULL AFTER current_service_description,
    ADD COLUMN current_schedule_note VARCHAR(500) NULL AFTER current_service_tags,
    ADD COLUMN current_cover_image VARCHAR(500) NULL AFTER current_schedule_note,
    ADD COLUMN current_images VARCHAR(2000) NULL AFTER current_cover_image,
    ADD COLUMN current_consultation_fee DECIMAL(10,2) NULL AFTER current_images,
    ADD COLUMN current_commission_rate DECIMAL(5,2) NULL AFTER current_consultation_fee,
    ADD COLUMN current_institution_rate DECIMAL(5,2) NULL AFTER current_commission_rate,
    ADD COLUMN current_platform_rate DECIMAL(5,2) NULL AFTER current_institution_rate,
    ADD COLUMN current_doctor_rate DECIMAL(5,2) NULL AFTER current_platform_rate;

-- Historical PROFILE_UPDATE rows predate immutable before snapshots. Capture an
-- auditable migration-time approximation from the exact doctor/project target.
-- Missing or soft-deleted configuration uses the service defaults in force when
-- this migration shipped; a missing doctor_project uses safe zero/empty values.
UPDATE doctor_project_change_requests request
LEFT JOIN doctor_projects project
    ON project.doctor_id = request.doctor_id
   AND project.institution_project_id = request.institution_project_id
LEFT JOIN doctor_institution_project_configs config
    ON config.doctor_id = request.doctor_id
   AND config.institution_project_id = request.institution_project_id
   AND config.deleted_at IS NULL
SET request.consultation_fee = COALESCE(request.consultation_fee, config.consultation_fee, 0.00),
    request.commission_rate = COALESCE(request.commission_rate, config.commission_rate, 0.00),
    request.institution_rate = COALESCE(request.institution_rate, config.institution_rate, 40.00),
    request.base_doctor_project_updated_at = project.updated_at,
    request.base_config_id = config.id,
    request.base_config_updated_at = config.updated_at,
    request.current_price = COALESCE(project.price, 0.00),
    request.current_service_description = COALESCE(project.service_description, ''),
    request.current_service_tags = COALESCE(project.service_tags, ''),
    request.current_schedule_note = COALESCE(project.schedule_note, ''),
    request.current_cover_image = COALESCE(project.cover_image, ''),
    request.current_images = COALESCE(project.images, ''),
    request.current_consultation_fee = COALESCE(config.consultation_fee, 0.00),
    request.current_commission_rate = COALESCE(config.commission_rate, 0.00),
    request.current_institution_rate = COALESCE(config.institution_rate, 40.00),
    request.current_platform_rate = 10.00,
    request.current_doctor_rate = 100.00 - 10.00
        - COALESCE(config.institution_rate, 40.00)
        - COALESCE(config.commission_rate, 0.00)
WHERE request.request_type = 'PROFILE_UPDATE';
