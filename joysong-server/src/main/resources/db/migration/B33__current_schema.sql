-- Flyway baseline for the schema produced by B26 followed by V27 through V33.
-- Generated from an empty isolated MySQL 8.0.39 database. Keep B1, B26, and V1-V33 unchanged
-- so existing databases can continue to validate and migrate normally. Add future changes as V34+.


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_assessments` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `status` varchar(30) NOT NULL,
  `completeness_score` int NOT NULL DEFAULT '0',
  `goal_snapshot_json` json NOT NULL,
  `risk_level` varchar(20) NOT NULL DEFAULT 'NONE',
  `risk_reasons_json` json NOT NULL,
  `missing_fields_json` json NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_assessment_user_created` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_messages` (
  `id` varchar(36) NOT NULL,
  `session_id` varchar(36) NOT NULL,
  `turn_id` varchar(36) DEFAULT NULL,
  `sequence_no` bigint NOT NULL,
  `role` varchar(16) NOT NULL,
  `content_type` varchar(16) NOT NULL DEFAULT 'TEXT',
  `content` text NOT NULL,
  `metadata_json` json NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_message_sequence` (`session_id`,`sequence_no`),
  KEY `fk_agent_message_turn` (`turn_id`),
  CONSTRAINT `fk_agent_message_session` FOREIGN KEY (`session_id`) REFERENCES `agent_sessions` (`id`),
  CONSTRAINT `fk_agent_message_turn` FOREIGN KEY (`turn_id`) REFERENCES `agent_turns` (`id`),
  CONSTRAINT `ck_agent_message_content_type` CHECK ((`content_type` in (_utf8mb4'TEXT',_utf8mb4'CATALOG',_utf8mb4'REPORT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_plan_items` (
  `id` varchar(36) NOT NULL,
  `plan_id` varchar(36) NOT NULL,
  `stage_name` varchar(50) NOT NULL,
  `project_id` varchar(36) DEFAULT NULL,
  `project_name` varchar(200) NOT NULL,
  `recommendation_type` varchar(30) NOT NULL,
  `reason_text` text NOT NULL,
  `expected_benefit` text NOT NULL,
  `limitations_text` text NOT NULL,
  `risks_json` json NOT NULL,
  `alternatives_json` json NOT NULL,
  `required_confirmation_json` json NOT NULL,
  `confidence` varchar(20) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_agent_plan_item_plan_sort` (`plan_id`,`sort_order`),
  CONSTRAINT `fk_agent_plan_item_plan` FOREIGN KEY (`plan_id`) REFERENCES `agent_plans` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_plans` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `assessment_id` varchar(36) NOT NULL,
  `version` int NOT NULL,
  `status` varchar(30) NOT NULL,
  `summary` text NOT NULL,
  `total_budget_min` decimal(10,2) DEFAULT NULL,
  `total_budget_max` decimal(10,2) DEFAULT NULL,
  `currency` char(3) NOT NULL DEFAULT 'CNY',
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_plan_user_version` (`user_id`,`version`),
  KEY `idx_agent_plan_assessment` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_safety_events` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `assessment_id` varchar(36) DEFAULT NULL,
  `risk_type` varchar(50) NOT NULL,
  `risk_code` varchar(64) NOT NULL DEFAULT '',
  `risk_level` varchar(20) NOT NULL,
  `rule_version` varchar(64) NOT NULL DEFAULT '',
  `evidence_json` json NOT NULL,
  `agent_action` varchar(50) NOT NULL,
  `review_status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_safety_user_created` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_sessions` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `persona` varchar(32) NOT NULL,
  `context_type` varchar(32) NOT NULL,
  `context_id` varchar(100) NOT NULL DEFAULT '',
  `title` varchar(100) NOT NULL DEFAULT '',
  `next_sequence_no` bigint NOT NULL DEFAULT '1',
  `summary_json` json NOT NULL,
  `summary_updated_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_session_user_updated` (`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_turns` (
  `id` varchar(36) NOT NULL,
  `session_id` varchar(36) NOT NULL,
  `sequence_no` bigint NOT NULL,
  `idempotency_key` varchar(100) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `running_guard` tinyint GENERATED ALWAYS AS (if((`status` = _utf8mb4'RUNNING'),1,NULL)) STORED,
  `trace_id` varchar(36) NOT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `fallback_used` tinyint(1) NOT NULL DEFAULT '0',
  `model_name` varchar(100) NOT NULL DEFAULT '',
  `prompt_version` varchar(64) NOT NULL DEFAULT '',
  `started_at` datetime(6) NOT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `total_duration_ms` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_turn_sequence` (`session_id`,`sequence_no`),
  UNIQUE KEY `uk_agent_turn_idempotency` (`session_id`,`idempotency_key`),
  UNIQUE KEY `uk_agent_turn_running` (`session_id`,`running_guard`),
  KEY `idx_agent_turn_lease` (`status`,`lease_expires_at`),
  CONSTRAINT `fk_agent_turn_session` FOREIGN KEY (`session_id`) REFERENCES `agent_sessions` (`id`),
  CONSTRAINT `ck_agent_turn_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_user_profiles` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `city` varchar(100) NOT NULL DEFAULT '',
  `goals_json` json NOT NULL,
  `budget_min` decimal(10,2) DEFAULT NULL,
  `budget_max` decimal(10,2) DEFAULT NULL,
  `acceptable_downtime_days` int DEFAULT NULL,
  `pain_tolerance` varchar(20) NOT NULL DEFAULT '',
  `preferences_json` json NOT NULL,
  `excluded_projects_json` json NOT NULL,
  `consent_version` varchar(50) NOT NULL DEFAULT '',
  `confirmed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_profile_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `auth_sessions` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `refresh_token_hash` varchar(128) NOT NULL,
  `active_role` varchar(40) NOT NULL DEFAULT 'USER',
  `active_institution_id` varchar(36) DEFAULT NULL,
  `client_type` varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  `device_name` varchar(200) DEFAULT '',
  `expires_at` timestamp NOT NULL,
  `revoked_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_sessions_refresh_token_hash` (`refresh_token_hash`),
  KEY `idx_auth_sessions_user_active` (`user_id`,`revoked_at`,`expires_at`),
  KEY `fk_auth_sessions_institution` (`active_institution_id`),
  CONSTRAINT `fk_auth_sessions_institution` FOREIGN KEY (`active_institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_auth_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `banners` (
  `id` varchar(36) NOT NULL,
  `title` varchar(200) NOT NULL,
  `subtitle` varchar(500) DEFAULT '',
  `image_url` varchar(500) DEFAULT '',
  `accent_color` varchar(20) DEFAULT '#E8A0BF',
  `sort_order` int DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_banners_sort_order` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comments` (
  `id` varchar(36) NOT NULL,
  `diary_id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `content` text,
  `parent_id` varchar(36) DEFAULT NULL,
  `reply_to_user_id` varchar(36) DEFAULT NULL,
  `like_count` int NOT NULL DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_comments_diary_id` (`diary_id`),
  KEY `idx_comments_user_id` (`user_id`),
  KEY `idx_comments_parent_id` (`parent_id`),
  KEY `idx_comments_diary_parent` (`diary_id`,`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `consultant_institution_change_requests` (
  `id` varchar(36) NOT NULL,
  `consultant_id` varchar(36) NOT NULL,
  `institution_id` varchar(36) NOT NULL,
  `action` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `request_note` varchar(1000) NOT NULL DEFAULT '',
  `review_note` varchar(1000) NOT NULL DEFAULT '',
  `submitted_by` varchar(36) NOT NULL,
  `reviewed_by` varchar(36) DEFAULT NULL,
  `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pending_key` varchar(73) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PENDING') then concat(`consultant_id`,_utf8mb4':',`institution_id`) else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consultant_institution_change_requests_pending` (`pending_key`),
  KEY `idx_consultant_institution_change_requests_consultant` (`consultant_id`,`submitted_at`),
  KEY `idx_consultant_institution_change_requests_institution` (`institution_id`,`status`,`submitted_at`),
  KEY `idx_consultant_institution_change_requests_status` (`status`,`submitted_at`),
  KEY `fk_consultant_institution_change_requests_submitted_by` (`submitted_by`),
  KEY `fk_consultant_institution_change_requests_reviewer` (`reviewed_by`),
  CONSTRAINT `fk_consultant_institution_change_requests_consultant` FOREIGN KEY (`consultant_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_consultant_institution_change_requests_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_consultant_institution_change_requests_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_consultant_institution_change_requests_submitted_by` FOREIGN KEY (`submitted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_consultant_institution_change_requests_action` CHECK ((`action` in (_latin1'JOIN',_latin1'LEAVE'))),
  CONSTRAINT `chk_consultant_institution_change_requests_review_note` CHECK (((`status` <> _latin1'REJECTED') or (trim(`review_note`) <> _latin1''))),
  CONSTRAINT `chk_consultant_institution_change_requests_status` CHECK ((`status` in (_latin1'PENDING',_latin1'APPROVED',_latin1'REJECTED',_latin1'WITHDRAWN')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `coupons` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '优惠券名称',
  `type` varchar(20) NOT NULL COMMENT 'FIXED(满减)/PERCENTAGE(折扣)',
  `discount_value` decimal(10,2) NOT NULL COMMENT '折扣值',
  `min_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '最低消费金额',
  `applicable_project_ids` varchar(500) DEFAULT NULL COMMENT '适用项目ID列表（逗号分隔）',
  `applicable_institution_ids` varchar(500) DEFAULT NULL COMMENT '适用机构ID列表（逗号分隔）',
  `total_count` int NOT NULL DEFAULT '0' COMMENT '发放总量（0=不限）',
  `issued_count` int NOT NULL DEFAULT '0' COMMENT '已发放数量',
  `used_count` int NOT NULL DEFAULT '0' COMMENT '已使用数量',
  `start_time` datetime NOT NULL COMMENT '有效期开始',
  `end_time` datetime NOT NULL COMMENT '有效期结束',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE/EXPIRED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_coupons_status` (`status`),
  KEY `idx_coupons_type` (`type`),
  KEY `idx_coupons_end_time` (`end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券定义表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `diaries` (
  `id` varchar(36) NOT NULL,
  `title` varchar(300) NOT NULL,
  `author_name` varchar(100) DEFAULT '',
  `author_avatar` varchar(500) DEFAULT '',
  `content` text,
  `images` varchar(2000) DEFAULT '',
  `before_images` varchar(2000) DEFAULT '',
  `after_images` varchar(2000) DEFAULT '',
  `like_count` int DEFAULT '0',
  `comment_count` int DEFAULT '0',
  `favorite_count` int DEFAULT '0',
  `rating` int DEFAULT '0',
  `tags` varchar(500) DEFAULT '',
  `publish_date` date DEFAULT NULL,
  `status` varchar(20) DEFAULT 'published',
  `user_id` varchar(36) DEFAULT '',
  `doctor_id` varchar(36) DEFAULT '',
  `doctor_name` varchar(255) DEFAULT '',
  `project_id` varchar(36) DEFAULT '',
  `project_name` varchar(255) DEFAULT '',
  `institution_id` varchar(36) DEFAULT '',
  `institution_name` varchar(255) DEFAULT '',
  `institution_project_id` varchar(36) DEFAULT '',
  `order_id` varchar(36) DEFAULT '',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_diaries_doctor` (`doctor_id`),
  KEY `idx_diaries_project` (`project_id`),
  KEY `idx_diaries_user_id` (`user_id`),
  KEY `idx_diaries_institution_id` (`institution_id`),
  KEY `idx_diaries_rating` (`rating`),
  KEY `idx_diaries_inst_proj` (`institution_project_id`),
  KEY `idx_diaries_status_publish` (`status`,`publish_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `diary_shares` (
  `id` varchar(36) NOT NULL,
  `diary_id` varchar(36) NOT NULL,
  `token_hash` char(64) NOT NULL,
  `expires_at` datetime DEFAULT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_access_at` datetime DEFAULT NULL,
  `access_count` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_diary_shares_token_hash` (`token_hash`),
  KEY `idx_diary_shares_diary` (`diary_id`),
  KEY `idx_diary_shares_active` (`token_hash`,`revoked_at`,`expires_at`),
  CONSTRAINT `fk_diary_shares_diary` FOREIGN KEY (`diary_id`) REFERENCES `diaries` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dm_conversations` (
  `id` varchar(36) NOT NULL,
  `conversation_type` varchar(30) NOT NULL DEFAULT 'DIRECT',
  `order_id` varchar(36) DEFAULT NULL,
  `user_a_id` varchar(36) NOT NULL COMMENT '用户A ID（字典序较小）',
  `user_b_id` varchar(36) NOT NULL COMMENT '用户B ID（字典序较大）',
  `last_message` text,
  `last_message_at` timestamp NULL DEFAULT NULL,
  `user_a_unread` int NOT NULL DEFAULT '0',
  `user_b_unread` int NOT NULL DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `direct_pair_key` varchar(73) GENERATED ALWAYS AS ((case when (`conversation_type` = _utf8mb4'DIRECT') then concat(least(`user_a_id`,`user_b_id`),_utf8mb4':',greatest(`user_a_id`,`user_b_id`)) else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dm_direct_pair` (`direct_pair_key`),
  UNIQUE KEY `uk_dm_order_service_conversation` (`order_id`),
  KEY `idx_dm_conv_user_a` (`user_a_id`,`last_message_at` DESC),
  KEY `idx_dm_conv_user_b` (`user_b_id`,`last_message_at` DESC),
  CONSTRAINT `fk_dm_conversation_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`),
  CONSTRAINT `chk_dm_conversation_scope` CHECK ((((`conversation_type` = _latin1'DIRECT') and (`order_id` is null)) or ((`conversation_type` = _latin1'ORDER_SERVICE') and (`order_id` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dm_messages` (
  `id` varchar(36) NOT NULL,
  `conversation_id` varchar(36) NOT NULL,
  `sender_id` varchar(36) NOT NULL,
  `content` text NOT NULL,
  `message_type` varchar(20) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/SYSTEM',
  `is_read` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dm_msg_conv_created` (`conversation_id`,`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctor_institution_change_requests` (
  `id` varchar(36) NOT NULL,
  `doctor_id` varchar(36) NOT NULL,
  `institution_id` varchar(36) NOT NULL,
  `action` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `request_note` varchar(1000) NOT NULL DEFAULT '',
  `review_note` varchar(1000) NOT NULL DEFAULT '',
  `submitted_by` varchar(36) NOT NULL,
  `reviewed_by` varchar(36) DEFAULT NULL,
  `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pending_key` varchar(73) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PENDING') then concat(`doctor_id`,_utf8mb4':',`institution_id`) else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doctor_institution_change_requests_pending` (`pending_key`),
  KEY `idx_doctor_institution_change_requests_doctor` (`doctor_id`,`submitted_at`),
  KEY `idx_doctor_institution_change_requests_institution` (`institution_id`,`status`,`submitted_at`),
  KEY `idx_doctor_institution_change_requests_status` (`status`,`submitted_at`),
  KEY `fk_doctor_institution_change_requests_submitted_by` (`submitted_by`),
  KEY `fk_doctor_institution_change_requests_reviewer` (`reviewed_by`),
  CONSTRAINT `fk_doctor_institution_change_requests_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `doctors` (`id`),
  CONSTRAINT `fk_doctor_institution_change_requests_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_doctor_institution_change_requests_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_doctor_institution_change_requests_submitted_by` FOREIGN KEY (`submitted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_doctor_institution_change_requests_action` CHECK ((`action` in (_utf8mb4'JOIN',_utf8mb4'LEAVE'))),
  CONSTRAINT `chk_doctor_institution_change_requests_review_note` CHECK (((`status` <> _utf8mb4'REJECTED') or (`review_note` <> _utf8mb4''))),
  CONSTRAINT `chk_doctor_institution_change_requests_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'WITHDRAWN')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctor_institution_project_configs` (
  `id` varchar(36) NOT NULL,
  `doctor_id` varchar(36) NOT NULL,
  `institution_project_id` varchar(36) NOT NULL,
  `medical_list_price` decimal(19,2) NOT NULL DEFAULT '0.00',
  `consultation_fee` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '面诊金（元）',
  `commission_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '医生佣金比例（%）',
  `institution_rate` decimal(5,2) NOT NULL DEFAULT '40.00' COMMENT '机构分成比例（%）',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp(6) NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doctor_ip` (`doctor_id`,`institution_project_id`),
  KEY `idx_doctor_ip_configs_doctor` (`doctor_id`),
  KEY `idx_dipc_institution_project` (`institution_project_id`),
  KEY `idx_dipc_deleted_at` (`deleted_at`),
  CONSTRAINT `chk_dipc_medical_price_cent` CHECK ((`medical_list_price` = round(`medical_list_price`,2)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctor_institutions` (
  `id` varchar(36) NOT NULL,
  `doctor_id` varchar(36) NOT NULL,
  `institution_id` varchar(36) NOT NULL,
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'APPROVED',
  `request_note` varchar(1000) NOT NULL DEFAULT '',
  `review_note` varchar(1000) NOT NULL DEFAULT '',
  `registration_no` varchar(100) DEFAULT '',
  `registration_file_id` varchar(36) DEFAULT NULL,
  `confirmed_by` varchar(36) DEFAULT NULL,
  `confirmed_at` timestamp NULL DEFAULT NULL,
  `revoked_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doctor_institution` (`doctor_id`,`institution_id`),
  KEY `idx_doctor_institutions_doctor` (`doctor_id`),
  KEY `idx_doctor_institutions_institution` (`institution_id`),
  KEY `idx_doctor_institutions_primary` (`doctor_id`,`is_primary`),
  KEY `idx_doctor_institutions_status` (`institution_id`,`status`),
  KEY `fk_doctor_institutions_file` (`registration_file_id`),
  KEY `fk_doctor_institutions_confirmer` (`confirmed_by`),
  CONSTRAINT `fk_doctor_institutions_confirmer` FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_doctor_institutions_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `doctors` (`id`),
  CONSTRAINT `fk_doctor_institutions_file` FOREIGN KEY (`registration_file_id`) REFERENCES `private_files` (`id`),
  CONSTRAINT `fk_doctor_institutions_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `chk_doctor_institutions_status` CHECK ((`status` in (_utf8mb4'APPROVED',_utf8mb4'REVOKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctor_project_change_requests` (
  `id` varchar(36) NOT NULL,
  `doctor_id` varchar(36) NOT NULL,
  `institution_id` varchar(36) NOT NULL,
  `institution_project_id` varchar(36) NOT NULL,
  `request_type` varchar(30) NOT NULL,
  `service_description` text,
  `price_suggestion` decimal(10,2) DEFAULT NULL,
  `medical_list_price` decimal(19,4) DEFAULT NULL,
  `consultation_fee` decimal(10,2) DEFAULT NULL,
  `commission_rate` decimal(5,2) DEFAULT NULL,
  `institution_rate` decimal(5,2) DEFAULT NULL,
  `base_doctor_project_updated_at` timestamp(6) NULL DEFAULT NULL,
  `base_config_id` varchar(36) DEFAULT NULL,
  `base_config_updated_at` timestamp(6) NULL DEFAULT NULL,
  `current_price` decimal(10,2) DEFAULT NULL,
  `current_medical_list_price` decimal(19,4) DEFAULT NULL,
  `current_service_description` text,
  `current_service_tags` text,
  `current_schedule_note` varchar(500) DEFAULT NULL,
  `current_cover_image` varchar(500) DEFAULT NULL,
  `current_images` text,
  `current_consultation_fee` decimal(10,2) DEFAULT NULL,
  `current_commission_rate` decimal(5,2) DEFAULT NULL,
  `current_institution_rate` decimal(5,2) DEFAULT NULL,
  `current_platform_rate` decimal(5,2) DEFAULT NULL,
  `current_doctor_rate` decimal(5,2) DEFAULT NULL,
  `notes` text,
  `service_tags` text,
  `schedule_note` varchar(500) DEFAULT '',
  `cover_image` varchar(500) DEFAULT '',
  `images` text,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `submitted_by` varchar(36) NOT NULL,
  `reviewed_by` varchar(36) DEFAULT NULL,
  `review_note` varchar(1000) DEFAULT '',
  `force_processed` tinyint(1) NOT NULL DEFAULT '0',
  `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pending_key` varchar(73) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PENDING') then concat(`doctor_id`,_utf8mb4':',`institution_project_id`) else NULL end)) STORED,
  `payload_version` int NOT NULL DEFAULT '1',
  `base_institution_project_version` bigint DEFAULT NULL,
  `base_platform_inheritance_hash` char(64) DEFAULT NULL,
  `pricing_policy_revision` varchar(100) DEFAULT NULL,
  `proposed_travel_ground_service_fee` decimal(18,2) DEFAULT NULL,
  `shared_changed` tinyint(1) DEFAULT NULL,
  `current_project_snapshot` json DEFAULT NULL,
  `proposed_project_snapshot` json DEFAULT NULL,
  `current_doctor_is_active` tinyint(1) DEFAULT NULL,
  `proposed_doctor_is_active` tinyint(1) DEFAULT NULL,
  `approval_audit_snapshot` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dpcr_single_pending` (`pending_key`),
  KEY `idx_dpcr_doctor_status` (`doctor_id`,`status`,`submitted_at`),
  KEY `idx_dpcr_institution_status` (`institution_id`,`status`,`submitted_at`),
  KEY `idx_dpcr_project_status` (`institution_project_id`,`status`),
  KEY `fk_dpcr_submitter` (`submitted_by`),
  KEY `fk_dpcr_reviewer` (`reviewed_by`),
  CONSTRAINT `fk_dpcr_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `doctors` (`id`),
  CONSTRAINT `fk_dpcr_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_dpcr_institution_project` FOREIGN KEY (`institution_project_id`) REFERENCES `institution_projects` (`id`),
  CONSTRAINT `fk_dpcr_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_dpcr_submitter` FOREIGN KEY (`submitted_by`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_dpcr_commission_rate` CHECK (((`commission_rate` is null) or ((`commission_rate` >= 0) and (`commission_rate` <= 100)))),
  CONSTRAINT `chk_dpcr_consultation_fee` CHECK (((`consultation_fee` is null) or ((`consultation_fee` >= 0) and (`consultation_fee` <= 99999999.99)))),
  CONSTRAINT `chk_dpcr_institution_rate` CHECK (((`institution_rate` is null) or ((`institution_rate` >= 0) and (`institution_rate` <= 100)))),
  CONSTRAINT `chk_dpcr_payload_version` CHECK ((`payload_version` in (1,2))),
  CONSTRAINT `chk_dpcr_price_suggestion` CHECK (((`price_suggestion` is null) or (`price_suggestion` >= 0))),
  CONSTRAINT `chk_dpcr_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'CHANGES_REQUESTED',_utf8mb4'WITHDRAWN'))),
  CONSTRAINT `chk_dpcr_type` CHECK ((`request_type` in (_utf8mb4'JOIN',_utf8mb4'PROFILE_UPDATE',_utf8mb4'LEAVE'))),
  CONSTRAINT `chk_dpcr_v1_legacy_shape` CHECK (((`payload_version` <> 1) or ((`service_tags` is not null) and (`images` is not null) and (((`request_type` = _latin1'PROFILE_UPDATE') and (`consultation_fee` is not null) and (`commission_rate` is not null) and (`institution_rate` is not null) and (`current_price` is not null) and (`current_consultation_fee` is not null) and (`current_commission_rate` is not null) and (`current_institution_rate` is not null) and (`current_platform_rate` is not null) and (`current_doctor_rate` is not null)) or ((`request_type` <> _latin1'PROFILE_UPDATE') and (`consultation_fee` is null) and (`commission_rate` is null) and (`institution_rate` is null) and (`current_price` is null) and (`current_consultation_fee` is null) and (`current_commission_rate` is null) and (`current_institution_rate` is null) and (`current_platform_rate` is null) and (`current_doctor_rate` is null)))))),
  CONSTRAINT `chk_dpcr_v2_versioned_shape` CHECK (((`payload_version` <> 2) or ((`price_suggestion` is null) and (`medical_list_price` is not null) and (`medical_list_price` >= 0) and (`current_price` is not null) and (`current_price` >= 0) and (`base_institution_project_version` is not null) and (`base_institution_project_version` >= 0) and (`base_platform_inheritance_hash` is not null) and (char_length(`base_platform_inheritance_hash`) = 64) and regexp_like(`base_platform_inheritance_hash`,_latin1'^[0-9a-f]{64}$') and (`pricing_policy_revision` is not null) and (char_length(`pricing_policy_revision`) > 0) and (`base_doctor_project_updated_at` is not null) and (((`base_config_id` is null) and (`base_config_updated_at` is null)) or ((`base_config_id` is not null) and (`base_config_updated_at` is not null))) and (`shared_changed` is not null) and (`current_project_snapshot` is not null) and (`proposed_project_snapshot` is not null) and (`current_doctor_is_active` is not null) and (`proposed_doctor_is_active` is not null) and (`current_platform_rate` is not null) and (`current_platform_rate` >= 0) and (`current_platform_rate` <= 100) and (`proposed_travel_ground_service_fee` is not null) and (`proposed_travel_ground_service_fee` >= 0))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctor_projects` (
  `doctor_id` varchar(36) NOT NULL,
  `project_id` varchar(36) NOT NULL,
  `institution_project_id` varchar(36) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `service_description` text,
  `service_tags` varchar(500) DEFAULT '',
  `schedule_note` varchar(500) DEFAULT '',
  `cover_image` varchar(500) DEFAULT '',
  `images` varchar(2000) DEFAULT '',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp(6) NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `is_active` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`doctor_id`,`institution_project_id`),
  KEY `idx_doctor_projects_doctor` (`doctor_id`),
  KEY `idx_doctor_projects_project` (`project_id`),
  KEY `idx_dp_inst_proj` (`institution_project_id`),
  KEY `idx_doctor_projects_public_lookup` (`institution_project_id`,`is_active`,`price`),
  CONSTRAINT `fk_doctor_projects_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `doctors` (`id`),
  CONSTRAINT `fk_doctor_projects_institution_project` FOREIGN KEY (`institution_project_id`) REFERENCES `institution_projects` (`id`),
  CONSTRAINT `fk_doctor_projects_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `chk_doctor_projects_price` CHECK ((`price` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `doctors` (
  `id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `title` varchar(100) DEFAULT '',
  `bio` text,
  `avatar` varchar(500) DEFAULT '',
  `institution_id` varchar(36) DEFAULT '',
  `institution_name` varchar(200) DEFAULT '',
  `rating` decimal(2,1) DEFAULT '4.5',
  `review_count` int DEFAULT '0',
  `specialties` varchar(500) DEFAULT '',
  `is_verified` tinyint(1) DEFAULT '0',
  `consultation_count` int DEFAULT '0',
  `case_count` int DEFAULT '0',
  `credentials` text,
  `credential_images` varchar(2000) DEFAULT '',
  `certification_tags` varchar(500) DEFAULT '',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `contact_phone` varchar(50) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `idx_doctors_institution_id` (`institution_id`),
  CONSTRAINT `fk_doctors_user` FOREIGN KEY (`id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `expert_articles` (
  `id` varchar(36) NOT NULL,
  `title` varchar(300) NOT NULL,
  `author_name` varchar(100) DEFAULT '',
  `doctor_id` varchar(36) DEFAULT '',
  `summary` text,
  `cover_image` varchar(500) DEFAULT '',
  `publish_date` date DEFAULT NULL,
  `content` text,
  `read_count` int DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_articles_doctor_id` (`doctor_id`),
  KEY `idx_articles_publish_date` (`publish_date` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `favorites` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `target_type` varchar(20) NOT NULL,
  `target_id` varchar(36) NOT NULL,
  `target_name` varchar(200) DEFAULT '',
  `target_image` varchar(500) DEFAULT '',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`,`target_type`,`target_id`),
  KEY `idx_user_type` (`user_id`,`target_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `follows` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `target_type` varchar(20) NOT NULL,
  `target_id` varchar(36) NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `identity_application_documents` (
  `application_id` varchar(36) NOT NULL,
  `file_id` varchar(36) NOT NULL,
  `document_type` varchar(50) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`application_id`,`file_id`),
  KEY `fk_identity_documents_file` (`file_id`),
  CONSTRAINT `fk_identity_documents_application` FOREIGN KEY (`application_id`) REFERENCES `identity_applications` (`id`),
  CONSTRAINT `fk_identity_documents_file` FOREIGN KEY (`file_id`) REFERENCES `private_files` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `identity_applications` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `role_code` varchar(40) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `application_data` json NOT NULL,
  `review_note` varchar(1000) DEFAULT '',
  `reviewed_by` varchar(36) DEFAULT NULL,
  `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_identity_applications_user_role` (`user_id`,`role_code`,`submitted_at`),
  KEY `idx_identity_applications_review_queue` (`status`,`submitted_at`),
  KEY `fk_identity_applications_reviewer` (`reviewed_by`),
  CONSTRAINT `fk_identity_applications_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_identity_applications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `institution_memberships` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `institution_id` varchar(36) NOT NULL,
  `member_role` varchar(40) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `request_note` varchar(1000) NOT NULL DEFAULT '',
  `review_note` varchar(1000) NOT NULL DEFAULT '',
  `confirmed_by` varchar(36) DEFAULT NULL,
  `confirmed_at` timestamp NULL DEFAULT NULL,
  `revoked_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_membership` (`user_id`,`institution_id`,`member_role`),
  KEY `idx_institution_memberships_institution_status` (`institution_id`,`status`),
  KEY `fk_institution_memberships_confirmer` (`confirmed_by`),
  CONSTRAINT `fk_institution_memberships_confirmer` FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_institution_memberships_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_institution_memberships_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_institution_memberships_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'REVOKED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `institution_projects` (
  `id` varchar(36) NOT NULL,
  `institution_id` varchar(36) NOT NULL,
  `project_id` varchar(36) NOT NULL,
  `name` varchar(200) DEFAULT NULL,
  `category` varchar(100) DEFAULT NULL,
  `description` text,
  `rating` decimal(2,1) DEFAULT NULL,
  `review_count` int DEFAULT NULL,
  `tags` varchar(500) DEFAULT NULL,
  `slogan` varchar(500) DEFAULT NULL,
  `detail_content` text,
  `price` decimal(10,2) NOT NULL DEFAULT '0.00',
  `original_price` decimal(10,2) DEFAULT NULL,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `cover_image` varchar(500) DEFAULT NULL,
  `images` varchar(2000) DEFAULT NULL,
  `sales_count` int DEFAULT '0',
  `is_active` tinyint(1) DEFAULT '1',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_proj` (`institution_id`,`project_id`),
  KEY `idx_ip_institution` (`institution_id`),
  KEY `idx_ip_project` (`project_id`),
  KEY `idx_ip_sales_count` (`sales_count` DESC),
  KEY `idx_ip_active_sales_count` (`is_active`,`sales_count` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `institutions` (
  `id` varchar(36) NOT NULL,
  `name` varchar(200) NOT NULL,
  `address` varchar(500) DEFAULT '',
  `city` varchar(100) DEFAULT '',
  `description` text,
  `cover_image` varchar(500) DEFAULT '',
  `images` varchar(2000) DEFAULT '',
  `rating` decimal(2,1) DEFAULT '4.5',
  `review_count` int DEFAULT '0',
  `is_verified` tinyint(1) DEFAULT '0',
  `established_year` int DEFAULT NULL,
  `certification_time` date DEFAULT NULL,
  `credentials` text,
  `credential_images` varchar(2000) DEFAULT '',
  `specialties` varchar(500) DEFAULT '',
  `tags` varchar(500) DEFAULT '',
  `contact_phone` varchar(50) DEFAULT '',
  `business_hours` varchar(200) DEFAULT '',
  `project_count` int DEFAULT '0',
  `doctor_count` int DEFAULT '0',
  `consultation_count` int DEFAULT '0',
  `user_count` int DEFAULT '0',
  `case_count` int DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_institutions_city` (`city`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `legal_document_contents` (
  `id` varchar(36) NOT NULL,
  `release_id` varchar(36) NOT NULL,
  `locale` varchar(8) NOT NULL,
  `title` varchar(200) NOT NULL DEFAULT '',
  `content_html` mediumtext NOT NULL,
  `content_sha256` char(64) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_legal_release_locale` (`release_id`,`locale`),
  CONSTRAINT `fk_legal_content_release` FOREIGN KEY (`release_id`) REFERENCES `legal_document_releases` (`id`),
  CONSTRAINT `ck_legal_content_locale` CHECK ((`locale` in (_latin1'zh-CN',_latin1'en-US')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `legal_document_releases` (
  `id` varchar(36) NOT NULL,
  `document_type` varchar(32) NOT NULL,
  `version` int NOT NULL,
  `status` varchar(16) NOT NULL,
  `change_summary` varchar(1000) NOT NULL DEFAULT '',
  `published_at` datetime(6) DEFAULT NULL,
  `published_by` varchar(36) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `created_by` varchar(36) NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `updated_by` varchar(36) NOT NULL,
  `lock_version` bigint NOT NULL DEFAULT '0',
  `active_draft_key` varchar(32) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'DRAFT') then `document_type` else NULL end)) STORED,
  `active_published_key` varchar(32) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PUBLISHED') then `document_type` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_legal_release_version` (`document_type`,`version`),
  UNIQUE KEY `uq_legal_active_draft` (`active_draft_key`),
  UNIQUE KEY `uq_legal_active_published` (`active_published_key`),
  CONSTRAINT `ck_legal_release_status` CHECK ((`status` in (_latin1'DRAFT',_latin1'PUBLISHED',_latin1'SUPERSEDED'))),
  CONSTRAINT `ck_legal_release_type` CHECK ((`document_type` in (_latin1'USER_AGREEMENT',_latin1'PRIVACY_POLICY')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `likes` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `target_type` varchar(20) NOT NULL,
  `target_id` varchar(36) NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_likes_user_target` (`user_id`,`target_type`,`target_id`),
  KEY `idx_likes_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `type` varchar(64) NOT NULL,
  `title` varchar(200) NOT NULL,
  `content` text,
  `target_type` varchar(64) DEFAULT '',
  `target_id` varchar(36) DEFAULT '',
  `is_read` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_notif_user_created` (`user_id`,`created_at` DESC),
  KEY `idx_notif_user_unread` (`user_id`,`is_read`),
  KEY `idx_notifications_deleted_at` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_status_logs` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_id` varchar(36) NOT NULL COMMENT '关联订单ID',
  `from_status` varchar(30) NOT NULL COMMENT '变更前状态',
  `to_status` varchar(30) NOT NULL COMMENT '变更后状态',
  `operator_id` varchar(36) DEFAULT NULL COMMENT '操作人ID',
  `operator_type` varchar(20) NOT NULL COMMENT 'USER/ADMIN/INSTITUTION/SYSTEM',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_logs_order_id` (`order_id`),
  KEY `idx_status_logs_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单状态变更审计日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `orders` (
  `id` varchar(36) NOT NULL,
  `order_no` varchar(50) DEFAULT NULL,
  `user_id` varchar(36) NOT NULL,
  `user_phone` varchar(30) DEFAULT '',
  `project_id` varchar(36) DEFAULT '',
  `project_name` varchar(200) NOT NULL,
  `institution_id` varchar(36) DEFAULT '',
  `consultant_id` varchar(36) NOT NULL DEFAULT '',
  `consultant_name` varchar(100) NOT NULL DEFAULT '',
  `consultant_avatar` varchar(500) DEFAULT NULL,
  `institution_name` varchar(200) DEFAULT '',
  `institution_project_id` varchar(36) DEFAULT '',
  `doctor_id` varchar(36) DEFAULT '',
  `doctor_name` varchar(100) DEFAULT '',
  `cover_image` varchar(500) DEFAULT '',
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `price` decimal(10,2) NOT NULL DEFAULT '0.00',
  `total_amount_minor` bigint DEFAULT NULL,
  `medical_list_price_minor` bigint DEFAULT NULL,
  `platform_service_rate_bps` int DEFAULT NULL,
  `travel_ground_service_fee_minor` bigint DEFAULT NULL,
  `paid_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '累计已付金额',
  `paid_amount_minor` bigint DEFAULT NULL,
  `consultation_fee` decimal(10,2) DEFAULT '0.00',
  `consultation_fee_minor` bigint DEFAULT NULL,
  `coupon_id` bigint unsigned DEFAULT NULL COMMENT '使用的优惠券ID',
  `user_coupon_id` bigint unsigned DEFAULT NULL COMMENT '用户优惠券记录ID',
  `discount_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '优惠金额',
  `discount_amount_minor` bigint DEFAULT NULL,
  `quantity` int DEFAULT '1',
  `remaining_amount` decimal(10,2) DEFAULT '0.00',
  `remaining_amount_minor` bigint DEFAULT NULL,
  `pricing_country` char(2) DEFAULT NULL,
  `status` varchar(30) NOT NULL,
  `payment_flow` varchar(40) NOT NULL DEFAULT 'LEGACY_MEDICAL',
  `transaction_method` varchar(50) DEFAULT '',
  `remark` varchar(500) DEFAULT '',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `appointment_time` timestamp NULL DEFAULT NULL,
  `payment_time` timestamp NULL DEFAULT NULL,
  `service_activated_at` datetime DEFAULT NULL,
  `qr_code` varchar(100) DEFAULT '',
  `evidence_url` varchar(2000) DEFAULT '',
  `has_review` tinyint(1) DEFAULT '0',
  `refund_status` varchar(20) DEFAULT 'NONE',
  `refund_amount` decimal(10,2) DEFAULT '0.00',
  `verified_at` datetime DEFAULT NULL COMMENT '到店核验时间',
  `verify_code` varchar(50) DEFAULT NULL COMMENT '核销码',
  `balance_paid_at` datetime DEFAULT NULL COMMENT '尾款支付时间',
  `completion_requested_at` datetime DEFAULT NULL COMMENT '机构申请完成时间',
  `completed_at` datetime DEFAULT NULL COMMENT '用户确认完成时间',
  `settlement_at` datetime DEFAULT NULL COMMENT '结算到期时间',
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `order_no` (`order_no`),
  KEY `idx_orders_user_id` (`user_id`),
  KEY `idx_orders_user_status` (`user_id`,`status`),
  KEY `idx_orders_inst_proj` (`institution_project_id`),
  KEY `idx_orders_status` (`status`),
  KEY `idx_orders_verify_code` (`verify_code`),
  KEY `idx_orders_deleted_at` (`deleted_at`),
  KEY `idx_orders_consultant_id` (`consultant_id`),
  CONSTRAINT `chk_orders_money_non_negative` CHECK (((`total_amount_minor` >= 0) and (`paid_amount_minor` >= 0) and (`consultation_fee_minor` >= 0) and (`discount_amount_minor` >= 0) and (`remaining_amount_minor` >= 0))),
  CONSTRAINT `chk_orders_platform_service_rate_bps` CHECK (((`platform_service_rate_bps` is null) or (`platform_service_rate_bps` between 0 and 10000))),
  CONSTRAINT `chk_orders_travel_ground_service_fee` CHECK (((`travel_ground_service_fee_minor` is null) or (`travel_ground_service_fee_minor` > 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_compensation_cases` (
  `id` varchar(36) NOT NULL,
  `payment_id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `provider` varchar(30) NOT NULL,
  `provider_payment_id` varchar(150) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `currency` char(3) NOT NULL,
  `reason_code` varchar(100) NOT NULL,
  `reason_message` varchar(500) NOT NULL DEFAULT '',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING_REVIEW',
  `idempotency_key` varchar(100) NOT NULL,
  `provider_refund_id` varchar(150) DEFAULT NULL,
  `reviewed_by` varchar(36) DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `failure_code` varchar(100) DEFAULT NULL,
  `failure_message` varchar(500) DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_compensation_cases_payment` (`payment_id`),
  UNIQUE KEY `uk_payment_compensation_cases_idempotency` (`idempotency_key`),
  UNIQUE KEY `uk_payment_compensation_cases_provider_refund` (`provider`,`provider_refund_id`),
  KEY `idx_payment_compensation_cases_status_updated` (`status`,`updated_at`),
  CONSTRAINT `fk_payment_compensation_cases_payment` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`),
  CONSTRAINT `chk_payment_compensation_cases_amount_positive` CHECK ((`amount_minor` > 0)),
  CONSTRAINT `chk_payment_compensation_cases_status` CHECK ((`status` in (_latin1'PENDING_REVIEW',_latin1'PROCESSING',_latin1'SUCCEEDED',_latin1'FAILED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_events` (
  `id` varchar(36) NOT NULL,
  `payment_id` varchar(36) DEFAULT NULL,
  `provider` varchar(30) NOT NULL,
  `provider_event_id` varchar(150) NOT NULL,
  `event_type` varchar(100) NOT NULL,
  `payload` longtext NOT NULL,
  `signature_valid` tinyint(1) NOT NULL DEFAULT '0',
  `processing_status` varchar(30) NOT NULL DEFAULT 'RECEIVED',
  `retry_count` int NOT NULL DEFAULT '0',
  `error_message` varchar(500) DEFAULT NULL,
  `received_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `processed_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_events_provider_event` (`provider`,`provider_event_id`),
  KEY `idx_payment_events_payment` (`payment_id`),
  KEY `idx_payment_events_processing` (`processing_status`,`received_at`),
  CONSTRAINT `fk_payment_events_payment` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payments` (
  `id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `amount` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `method` varchar(50) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `failure_code` varchar(100) DEFAULT NULL,
  `failure_message` varchar(500) DEFAULT NULL,
  `paid_at` timestamp NULL DEFAULT NULL,
  `transaction_id` varchar(100) DEFAULT '',
  `provider_payment_id` varchar(150) DEFAULT NULL,
  `provider_transaction_id` varchar(150) DEFAULT NULL,
  `idempotency_key` varchar(100) DEFAULT NULL,
  `payment_type` varchar(30) NOT NULL DEFAULT 'CONSULTATION_FEE' COMMENT 'CONSULTATION_FEE/BALANCE',
  `provider` varchar(30) NOT NULL DEFAULT 'DEMO',
  `payment_method` varchar(50) DEFAULT NULL,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `amount_minor` bigint DEFAULT NULL,
  `refunded_amount_minor` bigint NOT NULL DEFAULT '0',
  `authorized_at` datetime DEFAULT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  `expires_at` datetime DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payments_provider_payment` (`provider`,`provider_payment_id`),
  UNIQUE KEY `uk_payments_user_idempotency` (`user_id`,`idempotency_key`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_payments_order_type_status` (`order_id`,`payment_type`,`status`),
  KEY `idx_payments_status_updated` (`status`,`updated_at`),
  KEY `idx_payments_provider_transaction` (`provider`,`provider_transaction_id`),
  CONSTRAINT `chk_payments_amount_positive` CHECK ((`amount_minor` > 0)),
  CONSTRAINT `chk_payments_refunded_amount` CHECK (((`refunded_amount_minor` >= 0) and (`refunded_amount_minor` <= `amount_minor`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_cooperation_agreements` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) DEFAULT NULL,
  `institution_id` varchar(36) DEFAULT NULL,
  `cooperation_role` varchar(40) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `agreement_file_id` varchar(36) NOT NULL,
  `business_entity_name` varchar(200) NOT NULL,
  `business_license_no` varchar(100) NOT NULL,
  `effective_from` date DEFAULT NULL,
  `effective_until` date DEFAULT NULL,
  `approved_by` varchar(36) DEFAULT NULL,
  `approved_at` timestamp NULL DEFAULT NULL,
  `terminated_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_platform_agreements_user` (`user_id`,`cooperation_role`,`status`),
  KEY `idx_platform_agreements_institution` (`institution_id`,`cooperation_role`,`status`),
  KEY `fk_platform_agreements_file` (`agreement_file_id`),
  KEY `fk_platform_agreements_approver` (`approved_by`),
  CONSTRAINT `fk_platform_agreements_approver` FOREIGN KEY (`approved_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_platform_agreements_file` FOREIGN KEY (`agreement_file_id`) REFERENCES `private_files` (`id`),
  CONSTRAINT `fk_platform_agreements_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_platform_agreements_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_platform_agreement_subject` CHECK (((`user_id` is null) <> (`institution_id` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `private_files` (
  `id` varchar(36) NOT NULL,
  `owner_user_id` varchar(36) NOT NULL,
  `purpose` varchar(40) NOT NULL,
  `storage_key` varchar(500) NOT NULL,
  `original_name` varchar(255) DEFAULT '',
  `content_type` varchar(100) NOT NULL,
  `size_bytes` bigint NOT NULL,
  `sha256` varchar(64) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_private_files_storage_key` (`storage_key`),
  KEY `idx_private_files_owner_purpose` (`owner_user_id`,`purpose`),
  CONSTRAINT `fk_private_files_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `professional_project_requests` (
  `id` varchar(36) NOT NULL,
  `request_type` varchar(20) NOT NULL,
  `doctor_id` varchar(36) NOT NULL,
  `institution_id` varchar(36) DEFAULT NULL,
  `project_id` varchar(36) DEFAULT NULL,
  `name` varchar(200) DEFAULT NULL,
  `category` varchar(100) DEFAULT NULL,
  `description` text,
  `tags` json DEFAULT (json_array()),
  `slogan` varchar(500) NOT NULL DEFAULT '',
  `detail_content` text,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `cover_image` varchar(500) NOT NULL DEFAULT '',
  `images` json DEFAULT (json_array()),
  `sales_count` int NOT NULL DEFAULT '0',
  `reference_price` decimal(10,2) DEFAULT '0.00',
  `category_tags` json DEFAULT (json_array()),
  `price` decimal(10,2) DEFAULT NULL,
  `original_price` decimal(10,2) DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT NULL,
  `consultation_fee` decimal(10,2) DEFAULT NULL,
  `commission_rate` decimal(5,2) DEFAULT NULL,
  `institution_rate` decimal(5,2) DEFAULT NULL,
  `notes` text,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `review_note` varchar(1000) DEFAULT NULL,
  `reviewed_by` varchar(36) DEFAULT NULL,
  `reviewed_at` timestamp NULL DEFAULT NULL,
  `resulting_project_id` varchar(36) DEFAULT NULL,
  `resulting_institution_project_id` varchar(36) DEFAULT NULL,
  `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pending_platform_key` varchar(400) GENERATED ALWAYS AS ((case when ((`request_type` = _utf8mb4'PLATFORM') and (`status` = _utf8mb4'PENDING')) then concat(`doctor_id`,_utf8mb4':',coalesce(`name`,_utf8mb4''),_utf8mb4':',coalesce(`category`,_utf8mb4'')) else NULL end)) STORED,
  `pending_institution_key` varchar(150) GENERATED ALWAYS AS ((case when ((`request_type` = _utf8mb4'INSTITUTION') and (`status` = _utf8mb4'PENDING')) then concat(`doctor_id`,_utf8mb4':',`institution_id`,_utf8mb4':',`project_id`) else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_request_pending_platform` (`pending_platform_key`),
  UNIQUE KEY `uk_project_request_pending_institution` (`pending_institution_key`),
  KEY `idx_project_requests_doctor` (`doctor_id`,`submitted_at`),
  KEY `idx_project_requests_institution` (`institution_id`,`status`,`submitted_at`),
  KEY `idx_project_requests_type_status` (`request_type`,`status`,`submitted_at`),
  KEY `fk_project_requests_project` (`project_id`),
  KEY `fk_project_requests_reviewer` (`reviewed_by`),
  KEY `fk_project_requests_result_project` (`resulting_project_id`),
  KEY `fk_project_requests_result_institution_project` (`resulting_institution_project_id`),
  CONSTRAINT `fk_project_requests_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `doctors` (`id`),
  CONSTRAINT `fk_project_requests_institution` FOREIGN KEY (`institution_id`) REFERENCES `institutions` (`id`),
  CONSTRAINT `fk_project_requests_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_project_requests_result_institution_project` FOREIGN KEY (`resulting_institution_project_id`) REFERENCES `institution_projects` (`id`),
  CONSTRAINT `fk_project_requests_result_project` FOREIGN KEY (`resulting_project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `fk_project_requests_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_project_requests_amounts` CHECK ((((`price` is null) or (`price` >= 0)) and ((`original_price` is null) or (`original_price` >= 0)) and ((`reference_price` is null) or (`reference_price` >= 0)) and ((`consultation_fee` is null) or (`consultation_fee` >= 0)))),
  CONSTRAINT `chk_project_requests_currency` CHECK ((`currency` in (_latin1'USD',_latin1'CNY'))),
  CONSTRAINT `chk_project_requests_is_active` CHECK (((`is_active` is null) or (`is_active` in (0,1)))),
  CONSTRAINT `chk_project_requests_rates` CHECK ((((`commission_rate` is null) or (`commission_rate` between 0 and 100)) and ((`institution_rate` is null) or (`institution_rate` between 0 and 100)) and ((`commission_rate` is null) or (`institution_rate` is null) or ((`commission_rate` + `institution_rate`) <= 100)))),
  CONSTRAINT `chk_project_requests_review_note` CHECK (((`status` <> _latin1'REJECTED') or ((`review_note` is not null) and (trim(`review_note`) <> _latin1'')))),
  CONSTRAINT `chk_project_requests_sales_count` CHECK ((`sales_count` >= 0)),
  CONSTRAINT `chk_project_requests_shape` CHECK ((((`request_type` = _latin1'PLATFORM') and (`institution_id` is null) and (`project_id` is null) and (`name` is not null) and (`category` is not null) and (`description` is not null) and (`reference_price` is not null) and (`price` is null) and (`original_price` is null) and (`is_active` is null) and (`consultation_fee` is null) and (`commission_rate` is null) and (`institution_rate` is null)) or ((`request_type` = _latin1'INSTITUTION') and (`institution_id` is not null) and (`project_id` is not null) and (`price` is not null) and (`is_active` is not null) and (`consultation_fee` is not null) and (`commission_rate` is not null) and (`institution_rate` is not null) and (`reference_price` is null) and (`category_tags` is null)))),
  CONSTRAINT `chk_project_requests_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'CHANGES_REQUESTED'))),
  CONSTRAINT `chk_project_requests_type` CHECK ((`request_type` in (_utf8mb4'PLATFORM',_utf8mb4'INSTITUTION')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `projects` (
  `id` varchar(36) NOT NULL,
  `name` varchar(200) NOT NULL,
  `cover_image` varchar(500) DEFAULT '',
  `category` varchar(100) DEFAULT '',
  `description` text,
  `rating` decimal(2,1) DEFAULT '4.5',
  `review_count` int DEFAULT '0',
  `tags` varchar(500) DEFAULT '',
  `images` varchar(2000) DEFAULT '',
  `sales_count` int DEFAULT '0',
  `category_tags` varchar(500) DEFAULT '',
  `reference_price` decimal(10,2) DEFAULT '0.00',
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `slogan` varchar(500) DEFAULT '',
  `detail_content` text,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_projects_category` (`category`),
  KEY `idx_projects_sales_count` (`sales_count` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reconciliation_issues` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `issue_type` varchar(50) NOT NULL,
  `object_type` varchar(30) NOT NULL,
  `object_id` varchar(100) NOT NULL,
  `expected_minor` bigint NOT NULL DEFAULT '0',
  `actual_minor` bigint NOT NULL DEFAULT '0',
  `currency` varchar(3) DEFAULT NULL,
  `severity` varchar(20) NOT NULL DEFAULT 'ERROR',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN',
  `occurrence_count` bigint NOT NULL DEFAULT '1',
  `first_detected_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_detected_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `details` text,
  `resolved_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_key` varchar(255) GENERATED ALWAYS AS ((case when (`resolved_at` is null) then concat(`issue_type`,_utf8mb4':',`object_type`,_utf8mb4':',`object_id`) else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reconciliation_issues_active` (`active_key`),
  KEY `idx_reconciliation_issues_unresolved` (`resolved_at`,`created_at`),
  KEY `idx_reconciliation_issues_status` (`status`,`last_detected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_tokens` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `token_hash` char(64) NOT NULL,
  `expires_at` datetime NOT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `replaced_by_token_id` varchar(36) DEFAULT NULL,
  `last_used_at` datetime DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_tokens_hash` (`token_hash`),
  KEY `idx_refresh_tokens_user_active` (`user_id`,`revoked_at`,`expires_at`),
  KEY `fk_refresh_tokens_replacement` (`replaced_by_token_id`),
  CONSTRAINT `fk_refresh_tokens_replacement` FOREIGN KEY (`replaced_by_token_id`) REFERENCES `refresh_tokens` (`id`),
  CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund_items` (
  `id` varchar(36) NOT NULL,
  `refund_id` varchar(36) NOT NULL,
  `payment_id` varchar(36) NOT NULL,
  `provider` varchar(30) NOT NULL,
  `currency` char(3) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `provider_refund_id` varchar(150) DEFAULT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'CREATED',
  `failure_code` varchar(100) DEFAULT NULL,
  `failure_message` varchar(500) DEFAULT NULL,
  `requested_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_items_provider_refund` (`provider`,`provider_refund_id`),
  KEY `idx_refund_items_refund` (`refund_id`),
  KEY `idx_refund_items_payment` (`payment_id`),
  KEY `idx_refund_items_status_updated` (`status`,`updated_at`),
  CONSTRAINT `fk_refund_items_payment` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`),
  CONSTRAINT `fk_refund_items_refund` FOREIGN KEY (`refund_id`) REFERENCES `refunds` (`id`),
  CONSTRAINT `chk_refund_items_amount_positive` CHECK ((`amount_minor` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refunds` (
  `id` varchar(36) NOT NULL,
  `refund_no` varchar(50) DEFAULT NULL,
  `order_id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `order_no` varchar(50) DEFAULT '',
  `project_name` varchar(100) DEFAULT '',
  `amount` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `requested_amount_minor` bigint DEFAULT NULL,
  `refunded_amount_minor` bigint NOT NULL DEFAULT '0',
  `refund_type` varchar(20) DEFAULT 'FULL',
  `refund_amount` decimal(19,4) DEFAULT '0.0000',
  `payment_amount` decimal(19,4) DEFAULT '0.0000',
  `payment_time` timestamp NULL DEFAULT NULL,
  `user_phone` varchar(20) DEFAULT '',
  `reason` varchar(500) NOT NULL,
  `reason_code` varchar(50) DEFAULT NULL,
  `description` text,
  `evidence_url` varchar(500) DEFAULT '',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `active_order_id` varchar(36) GENERATED ALWAYS AS ((case when (`status` in (_utf8mb4'PENDING',_utf8mb4'PENDING_REVIEW',_utf8mb4'APPROVED',_utf8mb4'REFUND_PROCESSING')) then `order_id` else NULL end)) STORED,
  `original_status` varchar(30) DEFAULT '' COMMENT '退款前订单状态',
  `reviewed_by` varchar(36) DEFAULT NULL,
  `reviewed_at` datetime DEFAULT NULL,
  `reject_reason` varchar(500) DEFAULT NULL,
  `requested_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `revenue_reversal_status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `revenue_reversed_at` datetime DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `processed_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refunds_refund_no` (`refund_no`),
  UNIQUE KEY `uk_refunds_single_active` (`active_order_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_refunds_user_status` (`user_id`,`status`),
  KEY `idx_refunds_revenue_reversal` (`status`,`revenue_reversal_status`,`id`),
  CONSTRAINT `chk_refunds_reason_code` CHECK ((`reason_code` in (_utf8mb4'CUSTOMER_REQUEST',_utf8mb4'DUPLICATE_PAYMENT',_utf8mb4'FRAUD_SUSPECTED',_utf8mb4'SERVICE_NOT_PROVIDED',_utf8mb4'SERVICE_NOT_AS_DESCRIBED',_utf8mb4'ORDER_CANCELLED',_utf8mb4'BALANCE_PAYMENT_TIMEOUT',_utf8mb4'CONSULTATION_NO_SHOW_TIMEOUT',_utf8mb4'OTHER',_utf8mb4'LEGACY'))),
  CONSTRAINT `chk_refunds_refunded_amount` CHECK (((`refunded_amount_minor` >= 0) and (`refunded_amount_minor` <= `requested_amount_minor`))),
  CONSTRAINT `chk_refunds_requested_amount` CHECK ((`requested_amount_minor` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reports` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `target_type` varchar(20) NOT NULL COMMENT 'diary | review | comment',
  `target_id` varchar(36) NOT NULL,
  `reason` varchar(50) NOT NULL COMMENT '预设原因',
  `description` text COMMENT '补充说明',
  `status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT 'pending | resolved | ignored',
  `target_summary` text COMMENT '举报时内容快照（JSON）',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '软删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`,`target_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reviews` (
  `id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  `doctor_id` varchar(36) DEFAULT '',
  `rating` int NOT NULL,
  `content` text,
  `tags` varchar(500) DEFAULT '',
  `images` varchar(2000) DEFAULT '',
  `target_type` varchar(20) DEFAULT '',
  `target_id` varchar(36) DEFAULT '',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `active_canonical_order_id` varchar(36) GENERATED ALWAYS AS ((case when ((`deleted_at` is null) and (`target_type` = _utf8mb4'INSTITUTION')) then `order_id` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reviews_active_canonical_order` (`active_canonical_order_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_reviews_target` (`target_type`,`target_id`),
  KEY `idx_reviews_doctor_type` (`doctor_id`,`target_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlement_allocations` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `settlement_id` bigint unsigned NOT NULL,
  `owner_type` varchar(20) NOT NULL,
  `owner_id` varchar(36) NOT NULL,
  `owner_name` varchar(200) NOT NULL,
  `rate` decimal(5,2) NOT NULL,
  `amount_minor` bigint NOT NULL,
  `reversed_minor` bigint NOT NULL DEFAULT '0',
  `balance_bucket` varchar(20) NOT NULL DEFAULT 'PENDING',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_allocation_settlement_owner` (`settlement_id`,`owner_type`,`owner_id`),
  CONSTRAINT `fk_allocation_settlement` FOREIGN KEY (`settlement_id`) REFERENCES `settlements` (`id`),
  CONSTRAINT `chk_allocation_amount` CHECK (((`amount_minor` >= 0) and (`reversed_minor` between 0 and `amount_minor`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settlements` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_id` varchar(36) NOT NULL COMMENT '关联订单ID',
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `total_amount` decimal(19,4) NOT NULL,
  `total_amount_minor` bigint DEFAULT NULL,
  `platform_amount` decimal(19,4) NOT NULL,
  `platform_amount_minor` bigint DEFAULT NULL,
  `institution_amount` decimal(19,4) NOT NULL,
  `institution_amount_minor` bigint DEFAULT NULL,
  `consultant_amount` decimal(19,4) NOT NULL DEFAULT '0.0000',
  `consultant_amount_minor` bigint DEFAULT NULL,
  `doctor_amount` decimal(19,4) NOT NULL,
  `doctor_amount_minor` bigint DEFAULT NULL,
  `platform_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '平台分成比例（%）',
  `institution_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '机构分成比例（%）',
  `consultant_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '咨询师佣金比例（%）',
  `doctor_rate` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT '医生收入比例（%）',
  `payout_provider` varchar(30) DEFAULT NULL,
  `provider_transfer_id` varchar(150) DEFAULT NULL,
  `failure_code` varchar(100) DEFAULT NULL,
  `failure_message` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED',
  `settled_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlements_order_id` (`order_id`),
  UNIQUE KEY `uk_settlements_provider_transfer` (`payout_provider`,`provider_transfer_id`),
  KEY `idx_settlements_order_id` (`order_id`),
  KEY `idx_settlements_status` (`status`),
  CONSTRAINT `chk_settlements_money_non_negative` CHECK (((`total_amount_minor` >= 0) and (`platform_amount_minor` >= 0) and (`institution_amount_minor` >= 0) and (`consultant_amount_minor` >= 0) and (`doctor_amount_minor` >= 0))),
  CONSTRAINT `chk_settlements_split_total` CHECK (((((`platform_amount_minor` + `institution_amount_minor`) + `consultant_amount_minor`) + `doctor_amount_minor`) = `total_amount_minor`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结算分账记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `split_config_proposals` (
  `id` varchar(36) NOT NULL,
  `config_id` varchar(36) DEFAULT NULL,
  `doctor_id` varchar(36) NOT NULL,
  `institution_project_id` varchar(36) NOT NULL,
  `consultation_fee` decimal(10,2) NOT NULL DEFAULT '0.00',
  `commission_rate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `institution_rate` decimal(5,2) NOT NULL DEFAULT '40.00',
  `proposer_user_id` varchar(36) NOT NULL,
  `proposer_side` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `doctor_confirmed_at` timestamp NULL DEFAULT NULL,
  `institution_confirmed_at` timestamp NULL DEFAULT NULL,
  `decided_by` varchar(36) DEFAULT NULL,
  `decision_note` varchar(1000) DEFAULT '',
  `submitted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `decided_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `pending_key` varchar(73) GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'PENDING') then concat(`doctor_id`,_utf8mb4':',`institution_project_id`) else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_split_proposal_single_pending` (`pending_key`),
  KEY `idx_split_proposal_doctor_status` (`doctor_id`,`status`,`submitted_at`),
  KEY `idx_split_proposal_project_status` (`institution_project_id`,`status`,`submitted_at`),
  KEY `fk_split_proposal_config` (`config_id`),
  KEY `fk_split_proposal_proposer` (`proposer_user_id`),
  KEY `fk_split_proposal_decider` (`decided_by`),
  CONSTRAINT `fk_split_proposal_config` FOREIGN KEY (`config_id`) REFERENCES `doctor_institution_project_configs` (`id`),
  CONSTRAINT `fk_split_proposal_decider` FOREIGN KEY (`decided_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_split_proposal_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `doctors` (`id`),
  CONSTRAINT `fk_split_proposal_project` FOREIGN KEY (`institution_project_id`) REFERENCES `institution_projects` (`id`),
  CONSTRAINT `fk_split_proposal_proposer` FOREIGN KEY (`proposer_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_split_proposal_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'APPROVED',_utf8mb4'REJECTED',_utf8mb4'WITHDRAWN'))),
  CONSTRAINT `chk_split_proposer_side` CHECK ((`proposer_side` in (_utf8mb4'DOCTOR',_utf8mb4'INSTITUTION')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_coupons` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` varchar(36) NOT NULL COMMENT '用户ID（UUID）',
  `coupon_id` bigint unsigned NOT NULL COMMENT '优惠券ID',
  `status` varchar(20) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
  `used_at` datetime DEFAULT NULL COMMENT '使用时间',
  `order_id` varchar(36) DEFAULT NULL COMMENT '关联订单ID（UUID）',
  `expire_at` datetime NOT NULL COMMENT '过期时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_coupons_user_id` (`user_id`),
  KEY `idx_user_coupons_coupon_id` (`coupon_id`),
  KEY `idx_user_coupons_status` (`status`),
  KEY `idx_user_coupons_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户优惠券表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_roles` (
  `user_id` varchar(36) NOT NULL,
  `role_code` varchar(40) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `source_application_id` varchar(36) DEFAULT NULL,
  `activated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `revoked_at` timestamp NULL DEFAULT NULL,
  `revoked_by` varchar(36) DEFAULT NULL,
  `revoke_reason` varchar(500) DEFAULT '',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`role_code`),
  KEY `idx_user_roles_role_status` (`role_code`,`status`),
  KEY `fk_user_roles_application` (`source_application_id`),
  KEY `fk_user_roles_revoker` (`revoked_by`),
  CONSTRAINT `fk_user_roles_application` FOREIGN KEY (`source_application_id`) REFERENCES `identity_applications` (`id`),
  CONSTRAINT `fk_user_roles_revoker` FOREIGN KEY (`revoked_by`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_user_roles_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` varchar(36) NOT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `email` varchar(200) DEFAULT NULL,
  `password_hash` varchar(255) NOT NULL,
  `nickname` varchar(100) DEFAULT '',
  `avatar` varchar(500) DEFAULT '',
  `gender` varchar(20) NOT NULL DEFAULT '',
  `city` varchar(100) DEFAULT '',
  `bio` varchar(500) DEFAULT '',
  `birthday` date DEFAULT NULL,
  `role` varchar(20) DEFAULT 'USER',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `credentials_updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `phone` (`phone`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wallet_ledger_entries` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `wallet_id` bigint unsigned NOT NULL,
  `allocation_id` bigint unsigned DEFAULT NULL,
  `entry_type` varchar(30) NOT NULL,
  `pending_delta_minor` bigint NOT NULL DEFAULT '0',
  `available_delta_minor` bigint NOT NULL DEFAULT '0',
  `frozen_delta_minor` bigint NOT NULL DEFAULT '0',
  `pending_balance_minor` bigint NOT NULL,
  `available_balance_minor` bigint NOT NULL,
  `frozen_balance_minor` bigint NOT NULL,
  `source_type` varchar(30) NOT NULL,
  `source_id` varchar(100) NOT NULL,
  `operation_key` varchar(150) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_ledger_operation` (`operation_key`),
  KEY `idx_wallet_ledger_wallet_created` (`wallet_id`,`created_at`),
  KEY `idx_wallet_ledger_source` (`source_type`,`source_id`),
  KEY `fk_wallet_ledger_allocation` (`allocation_id`),
  CONSTRAINT `fk_wallet_ledger_allocation` FOREIGN KEY (`allocation_id`) REFERENCES `settlement_allocations` (`id`),
  CONSTRAINT `fk_wallet_ledger_wallet` FOREIGN KEY (`wallet_id`) REFERENCES `wallets` (`id`),
  CONSTRAINT `chk_wallet_ledger_balance_snapshots_non_negative` CHECK (((`pending_balance_minor` >= 0) and (`available_balance_minor` >= 0) and (`frozen_balance_minor` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wallets` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `owner_type` varchar(20) NOT NULL,
  `owner_id` varchar(36) NOT NULL,
  `currency` char(3) NOT NULL,
  `pending_minor` bigint NOT NULL DEFAULT '0',
  `available_minor` bigint NOT NULL DEFAULT '0',
  `frozen_minor` bigint NOT NULL DEFAULT '0',
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_owner_currency` (`owner_type`,`owner_id`,`currency`),
  CONSTRAINT `chk_wallet_balances_non_negative` CHECK (((`pending_minor` >= 0) and (`available_minor` >= 0) and (`frozen_minor` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
