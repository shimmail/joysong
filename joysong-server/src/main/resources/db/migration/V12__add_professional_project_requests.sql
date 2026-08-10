ALTER TABLE doctor_projects
    ADD COLUMN price DECIMAL(10, 2) NULL AFTER institution_project_id;

DELETE dp FROM doctor_projects dp
LEFT JOIN institution_projects ip ON ip.id = dp.institution_project_id
WHERE ip.id IS NULL;

UPDATE doctor_projects dp
JOIN institution_projects ip ON ip.id = dp.institution_project_id
SET dp.price = ip.price;

ALTER TABLE doctor_projects
    MODIFY COLUMN price DECIMAL(10, 2) NOT NULL,
    ADD CONSTRAINT chk_doctor_projects_price CHECK (price >= 0);

ALTER TABLE doctor_project_change_requests
    ADD COLUMN price_suggestion DECIMAL(10, 2) DEFAULT NULL AFTER service_description,
    ADD COLUMN notes TEXT DEFAULT NULL AFTER price_suggestion,
    DROP CHECK chk_dpcr_status,
    ADD CONSTRAINT chk_dpcr_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED', 'WITHDRAWN')),
    ADD CONSTRAINT chk_dpcr_price_suggestion
        CHECK (price_suggestion IS NULL OR price_suggestion >= 0);

CREATE TABLE professional_project_requests (
    id VARCHAR(36) NOT NULL,
    request_type VARCHAR(20) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) DEFAULT NULL,
    project_id VARCHAR(36) DEFAULT NULL,
    name VARCHAR(200) DEFAULT NULL,
    category VARCHAR(100) DEFAULT NULL,
    description TEXT DEFAULT NULL,
    service_content TEXT DEFAULT NULL,
    price_suggestion DECIMAL(10, 2) DEFAULT NULL,
    notes TEXT DEFAULT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    review_note VARCHAR(1000) DEFAULT NULL,
    reviewed_by VARCHAR(36) DEFAULT NULL,
    reviewed_at TIMESTAMP NULL DEFAULT NULL,
    resulting_project_id VARCHAR(36) DEFAULT NULL,
    resulting_institution_project_id VARCHAR(36) DEFAULT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    pending_platform_key VARCHAR(400) GENERATED ALWAYS AS (
        CASE WHEN request_type = 'PLATFORM' AND status = 'PENDING'
             THEN CONCAT(doctor_id, ':', COALESCE(name, ''), ':', COALESCE(category, ''))
             ELSE NULL END
    ) STORED,
    pending_institution_key VARCHAR(150) GENERATED ALWAYS AS (
        CASE WHEN request_type = 'INSTITUTION' AND status = 'PENDING'
             THEN CONCAT(doctor_id, ':', institution_id, ':', project_id)
             ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_request_pending_platform (pending_platform_key),
    UNIQUE KEY uk_project_request_pending_institution (pending_institution_key),
    KEY idx_project_requests_doctor (doctor_id, submitted_at),
    KEY idx_project_requests_institution (institution_id, status, submitted_at),
    KEY idx_project_requests_type_status (request_type, status, submitted_at),
    CONSTRAINT fk_project_requests_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    CONSTRAINT fk_project_requests_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_project_requests_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_requests_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT fk_project_requests_result_project FOREIGN KEY (resulting_project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_requests_result_institution_project
        FOREIGN KEY (resulting_institution_project_id) REFERENCES institution_projects(id),
    CONSTRAINT chk_project_requests_type CHECK (request_type IN ('PLATFORM', 'INSTITUTION')),
    CONSTRAINT chk_project_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CHANGES_REQUESTED')),
    CONSTRAINT chk_project_requests_shape CHECK (
        (request_type = 'PLATFORM' AND institution_id IS NULL AND project_id IS NULL
            AND name IS NOT NULL AND category IS NOT NULL AND description IS NOT NULL
            AND service_content IS NULL AND price_suggestion IS NULL)
        OR
        (request_type = 'INSTITUTION' AND institution_id IS NOT NULL AND project_id IS NOT NULL
            AND name IS NULL AND category IS NULL AND description IS NULL
            AND service_content IS NOT NULL AND price_suggestion IS NOT NULL)
    ),
    CONSTRAINT chk_project_requests_price CHECK (price_suggestion IS NULL OR price_suggestion >= 0),
    CONSTRAINT chk_project_requests_review_note CHECK (
        status NOT IN ('REJECTED', 'CHANGES_REQUESTED') OR review_note IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
