ALTER TABLE `users`
    ADD COLUMN `account_state` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER `credentials_updated_at`,
    ADD COLUMN `erased_at` DATETIME NULL AFTER `account_state`,
    ADD COLUMN `erased_phone_digest` CHAR(64) NULL AFTER `erased_at`,
    ADD COLUMN `erased_email_digest` CHAR(64) NULL AFTER `erased_phone_digest`,
    ADD INDEX `idx_users_account_state` (`account_state`);

CREATE TABLE `account_deletion_requests` (
    `id` VARCHAR(64) NOT NULL,
    `user_id` VARCHAR(64) NOT NULL,
    `step_up_method` VARCHAR(32) NOT NULL,
    `verification_code_hash` CHAR(64) NULL,
    `authorization_hash` CHAR(64) NULL,
    `idempotency_key_hash` CHAR(64) NOT NULL,
    `attempt_count` INT NOT NULL DEFAULT 0,
    `max_attempts` INT NOT NULL DEFAULT 5,
    `expires_at` DATETIME NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `terminal_status` VARCHAR(32) NULL,
    `completed_at` DATETIME NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_deletion_request_idempotency` (`user_id`, `idempotency_key_hash`),
    KEY `idx_account_deletion_requests_user_status` (`user_id`, `status`),
    KEY `idx_account_deletion_requests_expiry` (`expires_at`),
    CONSTRAINT `fk_account_deletion_requests_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_media_assets` (
    `id` VARCHAR(64) NOT NULL,
    `owner_user_id` VARCHAR(64) NOT NULL,
    `storage_key` VARCHAR(512) NOT NULL,
    `asset_type` VARCHAR(32) NOT NULL,
    `delete_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    `delete_attempt_count` INT NOT NULL DEFAULT 0,
    `retry_after` DATETIME NULL,
    `last_delete_attempt_at` DATETIME NULL,
    `last_delete_error` VARCHAR(512) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_media_assets_storage_key` (`storage_key`),
    KEY `idx_user_media_assets_owner_status` (`owner_user_id`, `delete_status`, `retry_after`),
    CONSTRAINT `fk_user_media_assets_owner`
        FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
