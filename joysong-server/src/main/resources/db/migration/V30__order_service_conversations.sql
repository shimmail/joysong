ALTER TABLE dm_conversations
    ADD COLUMN conversation_type VARCHAR(30) NOT NULL DEFAULT 'DIRECT' AFTER id,
    ADD COLUMN order_id VARCHAR(36) NULL AFTER conversation_type,
    DROP INDEX uk_dm_users,
    ADD COLUMN direct_pair_key VARCHAR(73)
        GENERATED ALWAYS AS (
            CASE
                WHEN conversation_type = 'DIRECT'
                    THEN CONCAT(LEAST(user_a_id, user_b_id), ':', GREATEST(user_a_id, user_b_id))
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_dm_direct_pair (direct_pair_key),
    ADD UNIQUE KEY uk_dm_order_service_conversation (order_id),
    ADD KEY idx_dm_conversation_order (order_id),
    ADD CONSTRAINT fk_dm_conversation_order
        FOREIGN KEY (order_id) REFERENCES orders(id),
    ADD CONSTRAINT chk_dm_conversation_scope CHECK (
        (conversation_type = 'DIRECT' AND order_id IS NULL)
        OR (conversation_type = 'ORDER_SERVICE' AND order_id IS NOT NULL)
    );
