CREATE TABLE doctor_institution_change_requests (
    id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    request_note VARCHAR(1000) NOT NULL DEFAULT '',
    review_note VARCHAR(1000) NOT NULL DEFAULT '',
    submitted_by VARCHAR(36) NOT NULL,
    reviewed_by VARCHAR(36) DEFAULT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    pending_key VARCHAR(73) GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING'
             THEN CONCAT(doctor_id, ':', institution_id)
             ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doctor_institution_change_requests_pending (pending_key),
    KEY idx_doctor_institution_change_requests_doctor (doctor_id, submitted_at),
    KEY idx_doctor_institution_change_requests_institution (institution_id, status, submitted_at),
    KEY idx_doctor_institution_change_requests_status (status, submitted_at),
    CONSTRAINT fk_doctor_institution_change_requests_doctor
        FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    CONSTRAINT fk_doctor_institution_change_requests_institution
        FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_doctor_institution_change_requests_submitted_by
        FOREIGN KEY (submitted_by) REFERENCES users(id),
    CONSTRAINT fk_doctor_institution_change_requests_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT chk_doctor_institution_change_requests_action
        CHECK (action IN ('JOIN', 'LEAVE')),
    CONSTRAINT chk_doctor_institution_change_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_doctor_institution_change_requests_review_note
        CHECK (status <> 'REJECTED' OR review_note <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO doctor_institution_change_requests (
    id,
    doctor_id,
    institution_id,
    action,
    status,
    request_note,
    review_note,
    submitted_by,
    reviewed_by,
    submitted_at,
    reviewed_at,
    created_at,
    updated_at
)
SELECT
    UUID(),
    doctor_id,
    institution_id,
    'JOIN',
    CASE
        WHEN status = 'CHANGES_REQUESTED' THEN 'REJECTED'
        WHEN status = 'REVOKED' THEN 'APPROVED'
        ELSE status
    END,
    request_note,
    CASE
        WHEN status IN ('REJECTED', 'CHANGES_REQUESTED')
             AND NULLIF(TRIM(COALESCE(review_note, '')), '') IS NULL
            THEN '历史审核未填写原因'
        ELSE COALESCE(review_note, '')
    END,
    doctor_id,
    confirmed_by,
    created_at,
    CASE
        WHEN status IN ('APPROVED', 'REVOKED') THEN confirmed_at
        WHEN status IN ('REJECTED', 'CHANGES_REQUESTED', 'WITHDRAWN') THEN updated_at
        ELSE NULL
    END,
    created_at,
    updated_at
FROM doctor_institutions;

UPDATE institution_memberships
SET status = 'REJECTED'
WHERE status = 'CHANGES_REQUESTED';

DELETE FROM doctor_institutions
WHERE status NOT IN ('APPROVED', 'REVOKED');
