SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS agent_plan_items;
DROP TABLE IF EXISTS agent_messages;
DROP TABLE IF EXISTS agent_turns;
DROP TABLE IF EXISTS agent_safety_events;
DROP TABLE IF EXISTS agent_plans;
DROP TABLE IF EXISTS agent_assessments;
DROP TABLE IF EXISTS agent_user_profiles;
DROP TABLE IF EXISTS agent_tool_audits;
DROP TABLE IF EXISTS agent_sessions;

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE agent_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    persona VARCHAR(32) NOT NULL,
    context_type VARCHAR(32) NOT NULL,
    context_id VARCHAR(100) NOT NULL DEFAULT '',
    title VARCHAR(100) NOT NULL DEFAULT '',
    next_sequence_no BIGINT NOT NULL DEFAULT 1,
    summary_json JSON NOT NULL,
    summary_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    INDEX idx_agent_session_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_turns (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    running_guard TINYINT GENERATED ALWAYS AS (IF(status = 'RUNNING', 1, NULL)) STORED,
    trace_id VARCHAR(36) NOT NULL,
    error_code VARCHAR(64) NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    model_name VARCHAR(100) NOT NULL DEFAULT '',
    prompt_version VARCHAR(64) NOT NULL DEFAULT '',
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_agent_turn_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT fk_agent_turn_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id),
    CONSTRAINT uk_agent_turn_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT uk_agent_turn_idempotency UNIQUE (session_id, idempotency_key),
    CONSTRAINT uk_agent_turn_running UNIQUE (session_id, running_guard)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_messages (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    turn_id VARCHAR(36) NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    content TEXT NOT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    CONSTRAINT ck_agent_message_content_type CHECK (content_type IN ('TEXT','CATALOG','REPORT')),
    CONSTRAINT fk_agent_message_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id),
    CONSTRAINT fk_agent_message_turn FOREIGN KEY (turn_id) REFERENCES agent_turns(id),
    CONSTRAINT uk_agent_message_sequence UNIQUE (session_id, sequence_no),
    INDEX idx_agent_message_session_sequence (session_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_user_profiles (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    city VARCHAR(100) NOT NULL DEFAULT '',
    goals_json JSON NOT NULL,
    budget_min DECIMAL(10,2) NULL,
    budget_max DECIMAL(10,2) NULL,
    acceptable_downtime_days INT NULL,
    pain_tolerance VARCHAR(20) NOT NULL DEFAULT '',
    preferences_json JSON NOT NULL,
    excluded_projects_json JSON NOT NULL,
    consent_version VARCHAR(50) NOT NULL DEFAULT '',
    confirmed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_agent_profile_user UNIQUE (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_assessments (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    completeness_score INT NOT NULL DEFAULT 0,
    goal_snapshot_json JSON NOT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'NONE',
    risk_reasons_json JSON NOT NULL,
    missing_fields_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_agent_assessment_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_safety_events (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) NULL,
    risk_type VARCHAR(50) NOT NULL,
    risk_code VARCHAR(64) NOT NULL DEFAULT '',
    risk_level VARCHAR(20) NOT NULL,
    rule_version VARCHAR(64) NOT NULL DEFAULT '',
    evidence_json JSON NOT NULL,
    agent_action VARCHAR(50) NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL,
    INDEX idx_agent_safety_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_plans (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary TEXT NOT NULL,
    total_budget_min DECIMAL(10,2) NULL,
    total_budget_max DECIMAL(10,2) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT uk_agent_plan_user_version UNIQUE (user_id, version),
    INDEX idx_agent_plan_assessment (assessment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_plan_items (
    id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL,
    stage_name VARCHAR(50) NOT NULL,
    project_id VARCHAR(36) NULL,
    project_name VARCHAR(200) NOT NULL,
    recommendation_type VARCHAR(30) NOT NULL,
    reason_text TEXT NOT NULL,
    expected_benefit TEXT NOT NULL,
    limitations_text TEXT NOT NULL,
    risks_json JSON NOT NULL,
    alternatives_json JSON NOT NULL,
    required_confirmation_json JSON NOT NULL,
    confidence VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_plan_item_plan FOREIGN KEY (plan_id) REFERENCES agent_plans(id),
    INDEX idx_agent_plan_item_plan_sort (plan_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
