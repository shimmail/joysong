ALTER TABLE reconciliation_issues
    DROP INDEX uk_reconciliation_issues_active,
    DROP COLUMN active_key,
    CHANGE COLUMN source_type object_type VARCHAR(30) NOT NULL,
    CHANGE COLUMN source_id object_id VARCHAR(100) NOT NULL,
    ADD COLUMN expected_minor BIGINT NOT NULL DEFAULT 0 AFTER object_id,
    ADD COLUMN actual_minor BIGINT NOT NULL DEFAULT 0 AFTER expected_minor,
    ADD COLUMN currency CHAR(3) NULL AFTER actual_minor,
    ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'ERROR' AFTER currency,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN' AFTER severity,
    ADD COLUMN occurrence_count BIGINT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN first_detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER occurrence_count,
    ADD COLUMN last_detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER first_detected_at,
    ADD COLUMN active_key VARCHAR(255) GENERATED ALWAYS AS (
        CASE WHEN resolved_at IS NULL THEN CONCAT(issue_type, ':', object_type, ':', object_id) ELSE NULL END
    ) STORED,
    ADD UNIQUE KEY uk_reconciliation_issues_active (active_key),
    ADD KEY idx_reconciliation_issues_status (status, last_detected_at);

UPDATE reconciliation_issues
SET first_detected_at = created_at,
    last_detected_at = updated_at,
    status = CASE WHEN resolved_at IS NULL THEN 'OPEN' ELSE 'RESOLVED' END;
