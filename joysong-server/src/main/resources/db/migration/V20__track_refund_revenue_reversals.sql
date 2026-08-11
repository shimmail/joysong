ALTER TABLE refunds
    ADD COLUMN revenue_reversal_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER completed_at,
    ADD COLUMN revenue_reversed_at DATETIME NULL AFTER revenue_reversal_status,
    ADD KEY idx_refunds_revenue_reversal (status, revenue_reversal_status, id);
