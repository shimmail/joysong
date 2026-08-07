CREATE TABLE diary_shares (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    diary_id VARCHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME NULL,
    revoked_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_access_at DATETIME NULL,
    access_count INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_diary_shares_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_diary_shares_diary FOREIGN KEY (diary_id) REFERENCES diaries(id)
);

CREATE INDEX idx_diary_shares_diary ON diary_shares(diary_id);
CREATE INDEX idx_diary_shares_active ON diary_shares(token_hash, revoked_at, expires_at);
