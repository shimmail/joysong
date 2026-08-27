ALTER TABLE orders
    ADD COLUMN pricing_policy_revision VARCHAR(80) NULL AFTER platform_service_rate_bps;

UPDATE orders
SET pricing_policy_revision = CONCAT(
    'travel-ground-service-rate:',
    CAST(platform_service_rate_bps / 10000 AS DECIMAL(10, 6))
)
WHERE payment_flow = 'TRAVEL_GROUND_SERVICE_ONLY';

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_travel_pricing_policy_revision
        CHECK (payment_flow <> 'TRAVEL_GROUND_SERVICE_ONLY' OR pricing_policy_revision IS NOT NULL);
