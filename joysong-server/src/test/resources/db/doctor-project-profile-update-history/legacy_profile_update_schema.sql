CREATE TABLE users (
    id VARCHAR(36) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE doctors (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE institutions (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE projects (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE institution_projects (
    id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    price DECIMAL(10,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE doctor_institutions (
    id VARCHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE doctor_projects (
    doctor_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    service_description TEXT NULL,
    service_tags VARCHAR(500) NOT NULL DEFAULT '',
    schedule_note VARCHAR(500) NOT NULL DEFAULT '',
    cover_image VARCHAR(500) NOT NULL DEFAULT '',
    images VARCHAR(2000) NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (doctor_id, institution_project_id)
) ENGINE=InnoDB;

CREATE TABLE doctor_institution_project_configs (
    id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    consultation_fee DECIMAL(10,2) NOT NULL,
    commission_rate DECIMAL(5,2) NOT NULL,
    institution_rate DECIMAL(5,2) NOT NULL,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE doctor_project_change_requests (
    id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    request_type VARCHAR(30) NOT NULL,
    service_description TEXT NULL,
    price_suggestion DECIMAL(10,2) NULL,
    service_tags VARCHAR(500) NOT NULL,
    schedule_note VARCHAR(500) NOT NULL,
    cover_image VARCHAR(500) NOT NULL,
    images VARCHAR(2000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    submitted_by VARCHAR(36) NOT NULL,
    review_note VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_dpcr_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
) ENGINE=InnoDB;
