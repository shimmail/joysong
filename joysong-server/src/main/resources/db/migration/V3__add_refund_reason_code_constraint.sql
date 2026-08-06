-- Normalize historical free-form reason codes before adding the constraint.
-- The human-readable refunds.reason field is retained, so no user reason text is lost.
UPDATE refunds
SET reason_code = UPPER(TRIM(reason_code))
WHERE reason_code IS NOT NULL;

UPDATE refunds
SET reason_code = 'OTHER'
WHERE reason_code IS NULL
   OR reason_code = ''
   OR reason_code NOT IN (
       'CUSTOMER_REQUEST',
       'DUPLICATE_PAYMENT',
       'FRAUD_SUSPECTED',
       'SERVICE_NOT_PROVIDED',
       'SERVICE_NOT_AS_DESCRIBED',
       'ORDER_CANCELLED',
       'BALANCE_PAYMENT_TIMEOUT',
       'CONSULTATION_NO_SHOW_TIMEOUT',
       'OTHER',
       'LEGACY'
   );

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_reason_code CHECK (
        reason_code IN (
            'CUSTOMER_REQUEST',
            'DUPLICATE_PAYMENT',
            'FRAUD_SUSPECTED',
            'SERVICE_NOT_PROVIDED',
            'SERVICE_NOT_AS_DESCRIBED',
            'ORDER_CANCELLED',
            'BALANCE_PAYMENT_TIMEOUT',
            'CONSULTATION_NO_SHOW_TIMEOUT',
            'OTHER',
            'LEGACY'
        )
    );
