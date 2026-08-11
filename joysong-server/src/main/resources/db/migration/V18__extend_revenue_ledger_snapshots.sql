ALTER TABLE settlement_allocations
    ADD COLUMN balance_bucket VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER reversed_minor;

ALTER TABLE wallet_ledger_entries
    ADD COLUMN pending_balance_minor BIGINT NOT NULL DEFAULT 0 AFTER frozen_delta_minor,
    ADD COLUMN available_balance_minor BIGINT NOT NULL DEFAULT 0 AFTER pending_balance_minor,
    ADD COLUMN frozen_balance_minor BIGINT NOT NULL DEFAULT 0 AFTER available_balance_minor;
