CREATE TABLE legal_document_releases (
    id VARCHAR(36) PRIMARY KEY,
    document_type VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    change_summary VARCHAR(1000) NOT NULL DEFAULT '',
    published_at DATETIME(6) NULL,
    published_by VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(36) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    active_draft_key VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 'DRAFT' THEN document_type ELSE NULL END
    ) STORED,
    active_published_key VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 'PUBLISHED' THEN document_type ELSE NULL END
    ) STORED,
    CONSTRAINT uq_legal_release_version UNIQUE (document_type, version),
    CONSTRAINT uq_legal_active_draft UNIQUE (active_draft_key),
    CONSTRAINT uq_legal_active_published UNIQUE (active_published_key),
    CONSTRAINT ck_legal_release_type CHECK (document_type IN ('USER_AGREEMENT', 'PRIVACY_POLICY')),
    CONSTRAINT ck_legal_release_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED'))
);

CREATE TABLE legal_document_contents (
    id VARCHAR(36) PRIMARY KEY,
    release_id VARCHAR(36) NOT NULL,
    locale VARCHAR(8) NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    content_html MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uq_legal_release_locale UNIQUE (release_id, locale),
    CONSTRAINT fk_legal_content_release FOREIGN KEY (release_id) REFERENCES legal_document_releases(id),
    CONSTRAINT ck_legal_content_locale CHECK (locale IN ('zh-CN', 'en-US'))
);
