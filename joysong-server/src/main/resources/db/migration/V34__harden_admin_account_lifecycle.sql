CREATE INDEX `idx_users_admin_lifecycle`
  ON `users` (`role`, `deleted_at`);

CREATE TABLE `admin_account_guard` (
  `guard_key` varchar(32) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`guard_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `admin_account_guard` (`guard_key`)
VALUES ('ACTIVE_ADMIN');
