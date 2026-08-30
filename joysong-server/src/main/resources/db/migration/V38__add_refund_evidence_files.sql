CREATE TABLE refund_evidence_files (
    file_id VARCHAR(36) NOT NULL,
    refund_id VARCHAR(36) NOT NULL,
    position SMALLINT UNSIGNED NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_refund_evidence_position (refund_id, position),
    KEY idx_refund_evidence_refund (refund_id),
    CONSTRAINT fk_refund_evidence_file
        FOREIGN KEY (file_id) REFERENCES private_files(id),
    CONSTRAINT fk_refund_evidence_refund
        FOREIGN KEY (refund_id) REFERENCES refunds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
