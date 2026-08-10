ALTER TABLE agent_turns
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER started_at,
    ADD INDEX idx_agent_turn_lease (status, lease_expires_at);
