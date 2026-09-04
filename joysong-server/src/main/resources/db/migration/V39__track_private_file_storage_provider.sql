ALTER TABLE `private_files`
    ADD COLUMN `storage_provider` VARCHAR(32) NOT NULL DEFAULT 'LOCAL_PRIVATE' AFTER `storage_key`,
    MODIFY COLUMN `storage_key` VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    ADD INDEX `idx_private_files_storage_provider` (`storage_provider`);

ALTER TABLE `user_media_assets`
    MODIFY COLUMN `storage_key` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;
