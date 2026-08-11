ALTER TABLE settlements
    ADD UNIQUE KEY uk_settlements_order_id (order_id);

CREATE TABLE settlement_allocations (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    settlement_id BIGINT UNSIGNED NOT NULL,
    owner_type VARCHAR(20) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    owner_name VARCHAR(200) NOT NULL,
    rate DECIMAL(5,2) NOT NULL,
    amount_minor BIGINT NOT NULL,
    reversed_minor BIGINT NOT NULL DEFAULT 0,
    balance_bucket VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_allocation_settlement_owner (settlement_id, owner_type, owner_id),
    CONSTRAINT chk_allocation_amount CHECK (amount_minor >= 0 AND reversed_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT fk_allocation_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wallets (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    currency CHAR(3) NOT NULL,
    pending_minor BIGINT NOT NULL DEFAULT 0,
    available_minor BIGINT NOT NULL DEFAULT 0,
    frozen_minor BIGINT NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wallet_owner_currency (owner_type, owner_id, currency),
    CONSTRAINT chk_wallet_balances_non_negative CHECK (pending_minor >= 0 AND available_minor >= 0 AND frozen_minor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wallet_ledger_entries (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    wallet_id BIGINT UNSIGNED NOT NULL,
    allocation_id BIGINT UNSIGNED NULL,
    entry_type VARCHAR(30) NOT NULL,
    pending_delta_minor BIGINT NOT NULL DEFAULT 0,
    available_delta_minor BIGINT NOT NULL DEFAULT 0,
    frozen_delta_minor BIGINT NOT NULL DEFAULT 0,
    pending_balance_minor BIGINT NOT NULL,
    available_balance_minor BIGINT NOT NULL,
    frozen_balance_minor BIGINT NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    operation_key VARCHAR(150) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wallet_ledger_operation (operation_key),
    KEY idx_wallet_ledger_wallet_created (wallet_id, created_at),
    KEY idx_wallet_ledger_source (source_type, source_id),
    CONSTRAINT fk_wallet_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id),
    CONSTRAINT fk_wallet_ledger_allocation FOREIGN KEY (allocation_id) REFERENCES settlement_allocations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reconciliation_issues (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    issue_type VARCHAR(50) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    details TEXT NULL,
    resolved_at DATETIME NULL,
    active_key VARCHAR(255) GENERATED ALWAYS AS (
        CASE WHEN resolved_at IS NULL THEN CONCAT(issue_type, ':', source_type, ':', source_id) ELSE NULL END
    ) STORED,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reconciliation_issues_active (active_key),
    KEY idx_reconciliation_issues_unresolved (resolved_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
