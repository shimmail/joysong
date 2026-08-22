CREATE TABLE payment_compensation_cases (
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_payment_id VARCHAR(150) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    reason_message VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    idempotency_key VARCHAR(100) NOT NULL,
    provider_refund_id VARCHAR(150) NULL,
    reviewed_by VARCHAR(36) NULL,
    reviewed_at DATETIME NULL,
    failure_code VARCHAR(100) NULL,
    failure_message VARCHAR(500) NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_compensation_cases_payment (payment_id),
    UNIQUE KEY uk_payment_compensation_cases_idempotency (idempotency_key),
    UNIQUE KEY uk_payment_compensation_cases_provider_refund (provider, provider_refund_id),
    KEY idx_payment_compensation_cases_status_updated (status, updated_at),
    CONSTRAINT fk_payment_compensation_cases_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT chk_payment_compensation_cases_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT chk_payment_compensation_cases_status CHECK (status IN ('PENDING_REVIEW', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
);

-- V29-V31 could already contain a verified late/duplicate travel payment.  Only
-- reconstruct cases whose original-channel refund facts are complete; mismatch
-- callbacks were not persisted before V32 and must not be fabricated here.
INSERT INTO payment_compensation_cases (
    id, payment_id, order_id, user_id, provider, provider_payment_id,
    amount_minor, currency, reason_code, reason_message, status,
    idempotency_key, created_at, updated_at
)
SELECT
    UUID(),
    p.id,
    p.order_id,
    p.user_id,
    UPPER(TRIM(p.provider)),
    TRIM(p.provider_payment_id),
    p.amount_minor,
    UPPER(TRIM(p.currency)),
    UPPER(TRIM(p.failure_code)),
    COALESCE(NULLIF(TRIM(p.failure_message), ''), 'Backfilled V29-V31 anomalous successful payment'),
    'PENDING_REVIEW',
    CONCAT('payment-compensation-', p.id),
    COALESCE(p.paid_at, p.updated_at, p.created_at, CURRENT_TIMESTAMP),
    COALESCE(p.updated_at, p.paid_at, p.created_at, CURRENT_TIMESTAMP)
FROM payments p
WHERE UPPER(TRIM(p.status)) IN ('SUCCEEDED', 'SUCCESS')
  AND UPPER(TRIM(p.failure_code)) IN (
      'DUPLICATE_PAYMENT_SUCCEEDED',
      'PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE'
  )
  AND UPPER(TRIM(p.payment_type)) = 'TRAVEL_GROUND_SERVICE_FEE'
  AND UPPER(TRIM(p.provider)) = 'ALIPAY_PLUS'
  AND UPPER(TRIM(p.currency)) = 'USD'
  AND p.amount_minor IS NOT NULL
  AND p.amount_minor > 0
  AND p.provider_payment_id IS NOT NULL
  AND TRIM(p.provider_payment_id) <> ''
  AND p.order_id IS NOT NULL
  AND TRIM(p.order_id) <> ''
  AND p.user_id IS NOT NULL
  AND TRIM(p.user_id) <> '';

-- Historical configuration values used four fractional digits.  Preserve every
-- row while applying the documented positive-value rounding rule before
-- tightening fractional precision.  Out-of-application-range values remain
-- visible for correction and are rejected by Money.requireUsdAmount on use.
UPDATE doctor_institution_project_configs
SET medical_list_price = ROUND(medical_list_price, 2);

ALTER TABLE doctor_institution_project_configs
    MODIFY COLUMN medical_list_price DECIMAL(19,2) NOT NULL DEFAULT 0.00;

ALTER TABLE doctor_institution_project_configs
    ADD CONSTRAINT chk_dipc_medical_price_cent
        CHECK (medical_list_price = ROUND(medical_list_price, 2));
