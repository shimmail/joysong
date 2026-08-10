INSERT INTO doctor_institution_change_requests (
    id,
    doctor_id,
    institution_id,
    action,
    status,
    request_note,
    review_note,
    submitted_by,
    submitted_at,
    created_at,
    updated_at
)
SELECT
    legacy.id,
    legacy.doctor_id,
    legacy.institution_id,
    'JOIN',
    'PENDING',
    legacy.request_note,
    legacy.review_note,
    legacy.doctor_id,
    legacy.created_at,
    legacy.created_at,
    legacy.updated_at
FROM doctor_institutions legacy
WHERE legacy.status = 'PENDING'
  AND NOT EXISTS (
      SELECT 1
      FROM doctor_institution_change_requests request
      WHERE request.doctor_id = legacy.doctor_id
        AND request.institution_id = legacy.institution_id
        AND request.status = 'PENDING'
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
