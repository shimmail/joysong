ALTER TABLE settlement_allocations
    ADD COLUMN balance_bucket VARCHAR(20) NULL AFTER reversed_minor;

UPDATE settlement_allocations allocation
LEFT JOIN (
    SELECT DISTINCT allocation_id
    FROM wallet_ledger_entries
    WHERE allocation_id IS NOT NULL AND entry_type = 'RELEASE'
) released ON released.allocation_id = allocation.id
SET allocation.balance_bucket = CASE
    WHEN allocation.status = 'AVAILABLE' OR released.allocation_id IS NOT NULL THEN 'AVAILABLE'
    ELSE 'PENDING'
END;

ALTER TABLE settlement_allocations
    MODIFY COLUMN balance_bucket VARCHAR(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE wallet_ledger_entries
    ADD COLUMN pending_balance_minor BIGINT NULL AFTER frozen_delta_minor,
    ADD COLUMN available_balance_minor BIGINT NULL AFTER pending_balance_minor,
    ADD COLUMN frozen_balance_minor BIGINT NULL AFTER available_balance_minor;

UPDATE wallet_ledger_entries entry
JOIN (
    SELECT id, pending_balance_minor, available_balance_minor, frozen_balance_minor
    FROM (
        SELECT
            id,
            SUM(pending_delta_minor) OVER (
                PARTITION BY wallet_id ORDER BY id ROWS UNBOUNDED PRECEDING
            ) AS pending_balance_minor,
            SUM(available_delta_minor) OVER (
                PARTITION BY wallet_id ORDER BY id ROWS UNBOUNDED PRECEDING
            ) AS available_balance_minor,
            SUM(frozen_delta_minor) OVER (
                PARTITION BY wallet_id ORDER BY id ROWS UNBOUNDED PRECEDING
            ) AS frozen_balance_minor
        FROM wallet_ledger_entries
    ) windowed
) snapshots ON snapshots.id = entry.id
SET entry.pending_balance_minor = snapshots.pending_balance_minor,
    entry.available_balance_minor = snapshots.available_balance_minor,
    entry.frozen_balance_minor = snapshots.frozen_balance_minor;

ALTER TABLE wallet_ledger_entries
    MODIFY COLUMN pending_balance_minor BIGINT NOT NULL,
    MODIFY COLUMN available_balance_minor BIGINT NOT NULL,
    MODIFY COLUMN frozen_balance_minor BIGINT NOT NULL,
    ADD CONSTRAINT chk_wallet_ledger_balance_snapshots_non_negative CHECK (
        pending_balance_minor >= 0 AND available_balance_minor >= 0 AND frozen_balance_minor >= 0
    );
