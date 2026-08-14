CREATE TABLE consultant_institution_change_requests (
    id VARCHAR(36) NOT NULL,
    consultant_id VARCHAR(36) NOT NULL,
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
             THEN CONCAT(consultant_id, ':', institution_id)
             ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consultant_institution_change_requests_pending (pending_key),
    KEY idx_consultant_institution_change_requests_consultant (consultant_id, submitted_at),
    KEY idx_consultant_institution_change_requests_institution (institution_id, status, submitted_at),
    KEY idx_consultant_institution_change_requests_status (status, submitted_at),
    KEY fk_consultant_institution_change_requests_submitted_by (submitted_by),
    KEY fk_consultant_institution_change_requests_reviewer (reviewed_by),
    CONSTRAINT fk_consultant_institution_change_requests_consultant
        FOREIGN KEY (consultant_id) REFERENCES users(id),
    CONSTRAINT fk_consultant_institution_change_requests_institution
        FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_consultant_institution_change_requests_submitted_by
        FOREIGN KEY (submitted_by) REFERENCES users(id),
    CONSTRAINT fk_consultant_institution_change_requests_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT chk_consultant_institution_change_requests_action
        CHECK (action IN ('JOIN', 'LEAVE')),
    CONSTRAINT chk_consultant_institution_change_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_consultant_institution_change_requests_review_note
        CHECK (status <> 'REJECTED' OR TRIM(review_note) <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO consultant_institution_change_requests (
    id,
    consultant_id,
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
    membership.id,
    membership.user_id,
    membership.institution_id,
    'JOIN',
    membership.status,
    membership.request_note,
    CASE
        WHEN membership.status = 'REJECTED'
             AND NULLIF(TRIM(COALESCE(membership.review_note, '')), '') IS NULL
            THEN '历史审核未填写原因'
        ELSE COALESCE(membership.review_note, '')
    END,
    membership.user_id,
    membership.confirmed_by,
    membership.created_at,
    CASE
        WHEN membership.status = 'PENDING' THEN NULL
        ELSE COALESCE(membership.confirmed_at, membership.updated_at)
    END,
    membership.created_at,
    membership.updated_at
FROM institution_memberships membership
WHERE membership.member_role = 'CONSULTANT'
  AND membership.status IN ('PENDING', 'REJECTED', 'APPROVED');
