-- Finalizer migration for a drained deployment. Previous binaries that still
-- write doctor_institutions PENDING/CHANGES_REQUESTED rows must be stopped
-- before this migration adds final status constraints.
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
    reviewed_at,
    submitted_at,
    created_at,
    updated_at
)
SELECT
    legacy.id,
    legacy.doctor_id,
    legacy.institution_id,
    'JOIN',
    CASE
        WHEN legacy.status = 'PENDING'
             AND EXISTS (
                 SELECT 1
                 FROM doctor_institution_change_requests pending_request
                 WHERE pending_request.doctor_id = legacy.doctor_id
                   AND pending_request.institution_id = legacy.institution_id
                   AND pending_request.status = 'PENDING'
             ) THEN 'REJECTED'
        WHEN legacy.status = 'CHANGES_REQUESTED' THEN 'REJECTED'
        ELSE legacy.status
    END,
    legacy.request_note,
    CASE
        WHEN legacy.status = 'PENDING'
             AND EXISTS (
                 SELECT 1
                 FROM doctor_institution_change_requests pending_request
                 WHERE pending_request.doctor_id = legacy.doctor_id
                   AND pending_request.institution_id = legacy.institution_id
                   AND pending_request.status = 'PENDING'
             ) THEN '历史待审核申请已由新关系申请接管'
        WHEN legacy.status IN ('REJECTED', 'CHANGES_REQUESTED')
             AND NULLIF(TRIM(COALESCE(legacy.review_note, '')), '') IS NULL
            THEN '历史审核未填写原因'
        ELSE COALESCE(legacy.review_note, '')
    END,
    legacy.doctor_id,
    legacy.confirmed_by,
    CASE
        WHEN legacy.status = 'PENDING'
             AND EXISTS (
                 SELECT 1
                 FROM doctor_institution_change_requests pending_request
                 WHERE pending_request.doctor_id = legacy.doctor_id
                   AND pending_request.institution_id = legacy.institution_id
                   AND pending_request.status = 'PENDING'
             ) THEN legacy.updated_at
        WHEN legacy.status IN ('REJECTED', 'CHANGES_REQUESTED')
            THEN COALESCE(legacy.confirmed_at, legacy.updated_at)
        ELSE legacy.confirmed_at
    END,
    legacy.created_at,
    legacy.created_at,
    legacy.updated_at
FROM doctor_institutions legacy
WHERE legacy.status IN ('PENDING', 'REJECTED', 'CHANGES_REQUESTED')
  AND NOT EXISTS (
      SELECT 1
      FROM doctor_institution_change_requests request
      WHERE request.id = legacy.id
  );

UPDATE institution_memberships
SET status = 'REJECTED'
WHERE status = 'CHANGES_REQUESTED';

DELETE FROM doctor_institutions
WHERE status NOT IN ('APPROVED', 'REVOKED');

ALTER TABLE doctor_institutions
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD CONSTRAINT chk_doctor_institutions_status
        CHECK (status IN ('APPROVED', 'REVOKED'));

ALTER TABLE institution_memberships
    ADD CONSTRAINT chk_institution_memberships_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'));
