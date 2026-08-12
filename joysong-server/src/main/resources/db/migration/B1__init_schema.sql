
-- ============================================================================
-- Consolidated from V1__init_schema.sql
-- ============================================================================

-- ============================================
-- 娇颜颂数据库初始化脚本（V1 权威基线）
-- 合并自 V1~V17 全部迁移脚本的最终状态
-- 包含 28 张表的最终结构、索引、约束
-- ============================================

-- 1. users（用户表）
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) NOT NULL,
    phone VARCHAR(20) DEFAULT NULL,
    email VARCHAR(200) DEFAULT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(100) DEFAULT '',
    avatar VARCHAR(500) DEFAULT '',
    gender VARCHAR(20) NOT NULL DEFAULT '',
    city VARCHAR(100) DEFAULT '',
    bio VARCHAR(500) DEFAULT '',
    birthday DATE NULL,
    role VARCHAR(20) DEFAULT 'USER',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    credentials_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY phone (phone),
    UNIQUE KEY uk_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 客户端登录会话。访问令牌使用短期 JWT，刷新令牌仅以 SHA-256 摘要落库并在每次刷新时轮换。
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME DEFAULT NULL,
    replaced_by_token_id VARCHAR(36) DEFAULT NULL,
    last_used_at DATETIME DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user_active (user_id, revoked_at, expires_at),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_refresh_tokens_replacement FOREIGN KEY (replaced_by_token_id) REFERENCES refresh_tokens(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 2. banners（首页轮播图）
CREATE TABLE IF NOT EXISTS banners (
    id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    subtitle VARCHAR(500) DEFAULT '',
    image_url VARCHAR(500) DEFAULT '',
    accent_color VARCHAR(20) DEFAULT '#E8A0BF',
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_banners_sort_order (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3. projects（医美项目模板表）
CREATE TABLE IF NOT EXISTS projects (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    cover_image VARCHAR(500) DEFAULT '',
    category VARCHAR(100) DEFAULT '',
    description TEXT,
    rating DECIMAL(2,1) DEFAULT 4.5,
    review_count INT DEFAULT 0,
    tags VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    sales_count INT DEFAULT 0,
    category_tags VARCHAR(500) DEFAULT '',
    reference_price DECIMAL(10,2) DEFAULT 0,
    slogan VARCHAR(500) DEFAULT '',
    detail_content TEXT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_projects_category (category),
    KEY idx_projects_sales_count (sales_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4. institutions（医美机构表）
CREATE TABLE IF NOT EXISTS institutions (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) DEFAULT '',
    city VARCHAR(100) DEFAULT '',
    district VARCHAR(100) DEFAULT '',
    description TEXT,
    cover_image VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    rating DECIMAL(2,1) DEFAULT 4.5,
    review_count INT DEFAULT 0,
    is_verified TINYINT(1) DEFAULT 0,
    established_year INT DEFAULT NULL,
    certification_time DATE NULL,
    credentials TEXT,
    credential_images VARCHAR(2000) DEFAULT '',
    specialties VARCHAR(500) DEFAULT '',
    tags VARCHAR(500) DEFAULT '',
    contact_phone VARCHAR(50) DEFAULT '',
    business_hours VARCHAR(200) DEFAULT '',
    project_count INT DEFAULT 0,
    doctor_count INT DEFAULT 0,
    consultation_count INT DEFAULT 0,
    user_count INT DEFAULT 0,
    case_count INT DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_institutions_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5. doctors（医生表）
CREATE TABLE IF NOT EXISTS doctors (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    title VARCHAR(100) DEFAULT '',
    bio TEXT,
    avatar VARCHAR(500) DEFAULT '',
    institution_id VARCHAR(36) DEFAULT '',
    institution_name VARCHAR(200) DEFAULT '',
    rating DECIMAL(2,1) DEFAULT 4.5,
    review_count INT DEFAULT 0,
    specialties VARCHAR(500) DEFAULT '',
    is_verified TINYINT(1) DEFAULT 0,
    consultation_count INT DEFAULT 0,
    case_count INT DEFAULT 0,
    credentials TEXT,
    credential_images VARCHAR(2000) DEFAULT '',
    certification_tags VARCHAR(500) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_doctors_institution_id (institution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 6. expert_articles（专家科普文章表）
CREATE TABLE IF NOT EXISTS expert_articles (
    id VARCHAR(36) NOT NULL,
    title VARCHAR(300) NOT NULL,
    author_name VARCHAR(100) DEFAULT '',
    doctor_id VARCHAR(36) DEFAULT '',
    summary TEXT,
    cover_image VARCHAR(500) DEFAULT '',
    publish_date DATE,
    content TEXT,
    read_count INT DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_articles_doctor_id (doctor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 7. diaries（用户日记表）
CREATE TABLE IF NOT EXISTS diaries (
    id VARCHAR(36) NOT NULL,
    title VARCHAR(300) NOT NULL,
    author_name VARCHAR(100) DEFAULT '',
    author_avatar VARCHAR(500) DEFAULT '',
    content TEXT,
    cover_image VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    before_images VARCHAR(2000) DEFAULT '',
    after_images VARCHAR(2000) DEFAULT '',
    like_count INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    favorite_count INT DEFAULT 0,
    rating INT DEFAULT 0,
    tags VARCHAR(500) DEFAULT '',
    publish_date DATE,
    status VARCHAR(20) DEFAULT 'published',
    user_id VARCHAR(36) DEFAULT '',
    doctor_id VARCHAR(36) DEFAULT '',
    doctor_name VARCHAR(255) DEFAULT '',
    project_id VARCHAR(36) DEFAULT '',
    project_name VARCHAR(255) DEFAULT '',
    institution_id VARCHAR(36) DEFAULT '',
    institution_name VARCHAR(255) DEFAULT '',
    institution_project_id VARCHAR(36) DEFAULT '',
    order_id VARCHAR(36) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_diaries_doctor (doctor_id),
    KEY idx_diaries_project (project_id),
    KEY idx_diaries_user_id (user_id),
    KEY idx_diaries_institution_id (institution_id),
    KEY idx_diaries_rating (rating),
    KEY idx_diaries_inst_proj (institution_project_id),
    KEY idx_diaries_status_publish (status, publish_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 8. orders（订单表）
-- 含完整生命周期字段（V4）+ 优惠券字段（V6）+ paid_amount（V13）+ completed_at（V14）
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(36) NOT NULL,
    order_no VARCHAR(50) DEFAULT NULL,
    user_id VARCHAR(36) NOT NULL,
    user_phone VARCHAR(30) DEFAULT '',
    project_id VARCHAR(36) DEFAULT '',
    project_name VARCHAR(200) NOT NULL,
    institution_id VARCHAR(36) DEFAULT '',
    institution_name VARCHAR(200) DEFAULT '',
    institution_project_id VARCHAR(36) DEFAULT '',
    doctor_id VARCHAR(36) DEFAULT '',
    doctor_name VARCHAR(100) DEFAULT '',
    cover_image VARCHAR(500) DEFAULT '',
    price DECIMAL(10,2) NOT NULL DEFAULT 0,
    paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '累计已付金额',
    consultation_fee DECIMAL(10,2) DEFAULT 0,
    coupon_id BIGINT UNSIGNED DEFAULT NULL COMMENT '使用的优惠券ID',
    user_coupon_id BIGINT UNSIGNED DEFAULT NULL COMMENT '用户优惠券记录ID',
    discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '优惠金额',
    quantity INT DEFAULT 1,
    remaining_amount DECIMAL(10,2) DEFAULT 0,
    status VARCHAR(30) NOT NULL,
    transaction_method VARCHAR(50) DEFAULT '',
    remark VARCHAR(500) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    appointment_time TIMESTAMP NULL,
    payment_time TIMESTAMP NULL,
    qr_code VARCHAR(100) DEFAULT '',
    evidence_url VARCHAR(2000) DEFAULT '',
    has_review TINYINT(1) DEFAULT 0,
    refund_status VARCHAR(20) DEFAULT 'NONE',
    refund_amount DECIMAL(10,2) DEFAULT 0,
    verified_at DATETIME DEFAULT NULL COMMENT '到店核验时间',
    verify_code VARCHAR(50) DEFAULT NULL COMMENT '核销码',
    balance_paid_at DATETIME DEFAULT NULL COMMENT '尾款支付时间',
    completion_requested_at DATETIME DEFAULT NULL COMMENT '机构申请完成时间',
    completed_at DATETIME DEFAULT NULL COMMENT '用户确认完成时间',
    settlement_at DATETIME DEFAULT NULL COMMENT '结算到期时间',
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY order_no (order_no),
    KEY idx_orders_user_id (user_id),
    KEY idx_orders_user_status (user_id, status),
    KEY idx_orders_inst_proj (institution_project_id),
    KEY idx_orders_status (status),
    KEY idx_orders_verify_code (verify_code),
    KEY idx_orders_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 9. follows（关注表）
CREATE TABLE IF NOT EXISTS follows (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 10. favorites（收藏表）
CREATE TABLE IF NOT EXISTS favorites (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    target_name VARCHAR(200) DEFAULT '',
    target_image VARCHAR(500) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_target (user_id, target_type, target_id),
    KEY idx_user_type (user_id, target_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 11. reviews（评价表）— V15: +doctor_id, -project_name
CREATE TABLE IF NOT EXISTS reviews (
    id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) DEFAULT '',
    rating INT NOT NULL,
    content TEXT,
    tags VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    target_type VARCHAR(20) DEFAULT '',
    target_id VARCHAR(36) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_user (user_id),
    KEY idx_reviews_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 12. payments（支付记录表）— V4: order_id UNIQUE→普通索引, +payment_type
CREATE TABLE IF NOT EXISTS payments (
    id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    method VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP NULL,
    transaction_id VARCHAR(100) DEFAULT '',
    payment_type VARCHAR(30) NOT NULL DEFAULT 'CONSULTATION_FEE' COMMENT 'CONSULTATION_FEE/BALANCE',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_order_type (order_id, payment_type),
    KEY idx_order_id (order_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 13. refunds（退款申请表）— V12: +original_status
CREATE TABLE IF NOT EXISTS refunds (
    id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    order_no VARCHAR(50) DEFAULT '',
    project_name VARCHAR(100) DEFAULT '',
    amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    refund_type VARCHAR(20) DEFAULT 'FULL',
    refund_amount DECIMAL(10,2) DEFAULT 0,
    payment_amount DECIMAL(10,2) DEFAULT 0,
    payment_time TIMESTAMP NULL,
    user_phone VARCHAR(20) DEFAULT '',
    reason VARCHAR(500) NOT NULL,
    description TEXT,
    evidence_url VARCHAR(500) DEFAULT '',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    original_status VARCHAR(30) DEFAULT '' COMMENT '退款前订单状态',
    active_order_id VARCHAR(36) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'APPROVED') THEN order_id ELSE NULL END
    ) STORED,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP NULL,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refunds_single_active (active_order_id),
    KEY idx_order (order_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 14. chat_sessions（AI聊天会话表）
CREATE TABLE IF NOT EXISTS chat_sessions (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    persona VARCHAR(20) NOT NULL DEFAULT 'BESTIE',
    context_type VARCHAR(20) NOT NULL DEFAULT 'GENERAL',
    context_id VARCHAR(36) DEFAULT '',
    title VARCHAR(200) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_sessions_user_id (user_id),
    KEY idx_chat_sessions_persona (persona)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 15. chat_messages（聊天消息表）
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_messages_session_id (session_id),
    KEY idx_chat_messages_session_created (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 16. likes（点赞表）
CREATE TABLE IF NOT EXISTS likes (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_likes_user_target (user_id, target_type, target_id),
    KEY idx_likes_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 17. comments（评论表 — 含楼中楼回复）
CREATE TABLE IF NOT EXISTS comments (
    id VARCHAR(36) NOT NULL,
    diary_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    content TEXT,
    parent_id VARCHAR(36) DEFAULT NULL,
    reply_to_user_id VARCHAR(36) DEFAULT NULL,
    like_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_comments_diary_id (diary_id),
    KEY idx_comments_user_id (user_id),
    KEY idx_comments_parent_id (parent_id),
    KEY idx_comments_diary_parent (diary_id, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 18. doctor_projects（医生-项目关联表）
CREATE TABLE IF NOT EXISTS doctor_projects (
    doctor_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    service_description TEXT,
    service_tags VARCHAR(500) DEFAULT '',
    schedule_note VARCHAR(500) DEFAULT '',
    cover_image VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (doctor_id, institution_project_id),
    KEY idx_doctor_projects_doctor (doctor_id),
    KEY idx_doctor_projects_project (project_id),
    KEY idx_dp_inst_proj (institution_project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 19. institution_projects（机构-项目关联表）
CREATE TABLE IF NOT EXISTS institution_projects (
    id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    price DECIMAL(10,2) NOT NULL DEFAULT 0,
    original_price DECIMAL(10,2) NULL DEFAULT NULL,
    cover_image VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    sales_count INT DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inst_proj (institution_id, project_id),
    KEY idx_ip_institution (institution_id),
    KEY idx_ip_project (project_id),
    KEY idx_ip_sales_count (sales_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 20. reports（举报记录表）
CREATE TABLE IF NOT EXISTS reports (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    target_type VARCHAR(20) NOT NULL COMMENT 'diary | review | comment',
    target_id VARCHAR(36) NOT NULL,
    reason VARCHAR(50) NOT NULL COMMENT '预设原因',
    description TEXT COMMENT '补充说明',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending | resolved | ignored',
    target_summary TEXT DEFAULT NULL COMMENT '举报时内容快照（JSON）',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',
    PRIMARY KEY (id),
    KEY idx_target (target_type, target_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 21. settlements（结算分账表）— V4创建, V8修正ID类型
CREATE TABLE IF NOT EXISTS settlements (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL COMMENT '关联订单ID',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    platform_amount DECIMAL(10,2) NOT NULL COMMENT '平台服务费',
    institution_amount DECIMAL(10,2) NOT NULL COMMENT '机构分成',
    consultant_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '咨询师佣金',
    doctor_amount DECIMAL(10,2) NOT NULL COMMENT '医生收入',
    platform_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '平台分成比例（%）',
    institution_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '机构分成比例（%）',
    consultant_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '咨询师佣金比例（%）',
    doctor_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '医生收入比例（%）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED',
    settled_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_settlements_order_id (order_id),
    INDEX idx_settlements_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算分账记录表';

-- 22. order_status_logs（状态变更审计日志表）— V4创建, V8修正ID类型
CREATE TABLE IF NOT EXISTS order_status_logs (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL COMMENT '关联订单ID',
    from_status VARCHAR(30) NOT NULL COMMENT '变更前状态',
    to_status VARCHAR(30) NOT NULL COMMENT '变更后状态',
    operator_id VARCHAR(36) DEFAULT NULL COMMENT '操作人ID',
    operator_type VARCHAR(20) NOT NULL COMMENT 'USER/ADMIN/INSTITUTION/SYSTEM',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status_logs_order_id (order_id),
    INDEX idx_status_logs_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态变更审计日志表';

-- 23. coupons（优惠券定义表）— V6创建
CREATE TABLE IF NOT EXISTS coupons (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '优惠券名称',
    type VARCHAR(20) NOT NULL COMMENT 'FIXED(满减)/PERCENTAGE(折扣)',
    discount_value DECIMAL(10,2) NOT NULL COMMENT '折扣值',
    min_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '最低消费金额',
    applicable_project_ids VARCHAR(500) DEFAULT NULL COMMENT '适用项目ID列表（逗号分隔）',
    applicable_institution_ids VARCHAR(500) DEFAULT NULL COMMENT '适用机构ID列表（逗号分隔）',
    total_count INT NOT NULL DEFAULT 0 COMMENT '发放总量（0=不限）',
    issued_count INT NOT NULL DEFAULT 0 COMMENT '已发放数量',
    used_count INT NOT NULL DEFAULT 0 COMMENT '已使用数量',
    start_time DATETIME NOT NULL COMMENT '有效期开始',
    end_time DATETIME NOT NULL COMMENT '有效期结束',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE/EXPIRED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    INDEX idx_coupons_status (status),
    INDEX idx_coupons_type (type),
    INDEX idx_coupons_end_time (end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券定义表';

-- 24. user_coupons（用户优惠券表）— V6创建, V7修正user_id/order_id为VARCHAR(36)
CREATE TABLE IF NOT EXISTS user_coupons (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL COMMENT '用户ID（UUID）',
    coupon_id BIGINT UNSIGNED NOT NULL COMMENT '优惠券ID',
    status VARCHAR(20) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED/USED/EXPIRED',
    used_at DATETIME DEFAULT NULL COMMENT '使用时间',
    order_id VARCHAR(36) DEFAULT NULL COMMENT '关联订单ID（UUID）',
    expire_at DATETIME NOT NULL COMMENT '过期时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_coupons_user_id (user_id),
    INDEX idx_user_coupons_coupon_id (coupon_id),
    INDEX idx_user_coupons_status (status),
    INDEX idx_user_coupons_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券表';

-- 25. doctor_institution_project_configs（医生机构项目配置表）
-- V5创建, V9+institution_rate, V10面诊金默认值→0, V11+deleted_at
CREATE TABLE IF NOT EXISTS doctor_institution_project_configs (
    id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    consultation_fee DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '面诊金（元）',
    commission_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '医生佣金比例（%）',
    institution_rate DECIMAL(5,2) NOT NULL DEFAULT 40.00 COMMENT '机构分成比例（%）',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doctor_ip (doctor_id, institution_project_id),
    KEY idx_doctor_ip_configs_doctor (doctor_id),
    KEY idx_dipc_institution_project (institution_project_id),
    KEY idx_dipc_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 26. notifications（系统通知表）— V16创建, V17+deleted_at
CREATE TABLE IF NOT EXISTS notifications (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    type VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    target_type VARCHAR(20) DEFAULT '',
    target_id VARCHAR(36) DEFAULT '',
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_notif_user_created (user_id, created_at DESC),
    KEY idx_notif_user_unread (user_id, is_read),
    KEY idx_notifications_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 27. dm_conversations（私信会话表）— V16创建
CREATE TABLE IF NOT EXISTS dm_conversations (
    id VARCHAR(36) NOT NULL,
    user_a_id VARCHAR(36) NOT NULL COMMENT '用户A ID（字典序较小）',
    user_b_id VARCHAR(36) NOT NULL COMMENT '用户B ID（字典序较大）',
    last_message TEXT,
    last_message_at TIMESTAMP NULL DEFAULT NULL,
    user_a_unread INT NOT NULL DEFAULT 0,
    user_b_unread INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dm_users (user_a_id, user_b_id),
    KEY idx_dm_conv_user_a (user_a_id, last_message_at DESC),
    KEY idx_dm_conv_user_b (user_b_id, last_message_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 28. dm_messages（私信消息表）— V16创建
CREATE TABLE IF NOT EXISTS dm_messages (
    id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    sender_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/SYSTEM',
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dm_msg_conv_created (conversation_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- Consolidated from V18__add_agent_planning.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_user_profiles (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    city VARCHAR(100) DEFAULT '',
    goals_json TEXT NOT NULL,
    budget_min DECIMAL(10,2) DEFAULT NULL,
    budget_max DECIMAL(10,2) DEFAULT NULL,
    acceptable_downtime_days INT DEFAULT NULL,
    pain_tolerance VARCHAR(20) DEFAULT '',
    preferences_json TEXT NOT NULL,
    excluded_projects_json TEXT NOT NULL,
    consent_version VARCHAR(50) DEFAULT '',
    confirmed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_profile_user (user_id),
    CONSTRAINT fk_agent_profile_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_assessments (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    completeness_score INT NOT NULL DEFAULT 0,
    goal_snapshot_json TEXT NOT NULL,
    risk_level VARCHAR(20) NOT NULL DEFAULT 'NONE',
    risk_reasons_json TEXT NOT NULL,
    missing_fields_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_assessment_user_created (user_id, created_at),
    CONSTRAINT fk_agent_assessment_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_plans (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary TEXT NOT NULL,
    total_budget_min DECIMAL(10,2) DEFAULT NULL,
    total_budget_max DECIMAL(10,2) DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_plan_user_version (user_id, version),
    KEY idx_agent_plan_assessment (assessment_id),
    CONSTRAINT fk_agent_plan_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_agent_plan_assessment FOREIGN KEY (assessment_id) REFERENCES agent_assessments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_plan_items (
    id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    stage_name VARCHAR(50) NOT NULL,
    project_id VARCHAR(36) DEFAULT NULL,
    project_name VARCHAR(200) NOT NULL,
    recommendation_type VARCHAR(30) NOT NULL,
    reason_text TEXT NOT NULL,
    expected_benefit TEXT NOT NULL,
    limitations_text TEXT NOT NULL,
    risks_json TEXT NOT NULL,
    alternatives_json TEXT NOT NULL,
    required_confirmation_json TEXT NOT NULL,
    confidence VARCHAR(20) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_plan_item_plan_sort (plan_id, sort_order),
    CONSTRAINT fk_agent_plan_item_plan FOREIGN KEY (plan_id) REFERENCES agent_plans(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_safety_events (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) DEFAULT NULL,
    risk_type VARCHAR(50) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    evidence_json TEXT NOT NULL,
    agent_action VARCHAR(50) NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_safety_user_created (user_id, created_at),
    CONSTRAINT fk_agent_safety_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS agent_tool_audits (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    request_summary TEXT NOT NULL,
    result_status VARCHAR(30) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_tool_audit_user_created (user_id, created_at),
    CONSTRAINT fk_agent_tool_audit_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- Consolidated from V19__rename_chat_tables_to_agent.sql
-- ============================================================================

-- The default AI conversation remains chat-shaped at the API layer, but its
-- persisted data belongs to the Agent domain. RENAME TABLE preserves rows,
-- indexes, timestamps, and session/message relationships.
RENAME TABLE
    chat_sessions TO agent_sessions,
    chat_messages TO agent_messages;

-- ============================================================================
-- Consolidated from V20__expand_agent_tool_audits.sql
-- ============================================================================

ALTER TABLE agent_tool_audits
    ADD COLUMN session_id VARCHAR(36) DEFAULT NULL AFTER user_id,
    ADD COLUMN intent VARCHAR(50) DEFAULT NULL AFTER request_summary,
    ADD COLUMN database_search BOOLEAN NOT NULL DEFAULT FALSE AFTER intent,
    ADD COLUMN detected_cities VARCHAR(500) NOT NULL DEFAULT '' AFTER database_search,
    ADD COLUMN detected_concerns VARCHAR(500) NOT NULL DEFAULT '' AFTER detected_cities,
    ADD COLUMN matched_entity_ids TEXT DEFAULT NULL AFTER detected_concerns,
    ADD COLUMN llm_called BOOLEAN NOT NULL DEFAULT FALSE AFTER matched_entity_ids,
    ADD COLUMN model_name VARCHAR(100) NOT NULL DEFAULT '' AFTER llm_called,
    ADD COLUMN gateway_url VARCHAR(255) NOT NULL DEFAULT '' AFTER model_name,
    ADD COLUMN total_duration_ms BIGINT NOT NULL DEFAULT 0 AFTER duration_ms,
    ADD COLUMN database_duration_ms BIGINT NOT NULL DEFAULT 0 AFTER total_duration_ms,
    ADD COLUMN llm_duration_ms BIGINT NOT NULL DEFAULT 0 AFTER database_duration_ms,
    ADD COLUMN http_status INT DEFAULT NULL AFTER llm_duration_ms,
    ADD COLUMN input_tokens INT DEFAULT NULL AFTER http_status,
    ADD COLUMN output_tokens INT DEFAULT NULL AFTER input_tokens,
    ADD COLUMN fallback_used BOOLEAN NOT NULL DEFAULT FALSE AFTER output_tokens,
    ADD COLUMN answer_length INT NOT NULL DEFAULT 0 AFTER fallback_used,
    ADD COLUMN error_summary VARCHAR(500) DEFAULT NULL AFTER answer_length,
    ADD KEY idx_agent_tool_audit_session_created (session_id, created_at),
    ADD KEY idx_agent_tool_audit_http_created (http_status, created_at);

-- ============================================================================
-- Consolidated from V21__rename_audit_cities_to_keywords.sql
-- ============================================================================

ALTER TABLE agent_tool_audits
    CHANGE COLUMN detected_cities detected_keywords VARCHAR(1000) NOT NULL DEFAULT '';

-- ============================================================================
-- Consolidated from V22__add_institution_project_detail_overrides.sql
-- ============================================================================

ALTER TABLE institution_projects
    ADD COLUMN name VARCHAR(200) NULL AFTER project_id,
    ADD COLUMN category VARCHAR(100) NULL AFTER name,
    ADD COLUMN description TEXT NULL AFTER category,
    ADD COLUMN rating DECIMAL(2, 1) NULL AFTER description,
    ADD COLUMN review_count INT NULL AFTER rating,
    ADD COLUMN tags VARCHAR(500) NULL AFTER review_count,
    ADD COLUMN slogan VARCHAR(500) NULL AFTER tags,
    ADD COLUMN detail_content TEXT NULL AFTER slogan;

-- ============================================================================
-- Consolidated from V23__add_doctor_institutions.sql
-- ============================================================================

CREATE TABLE doctor_institutions (
    id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doctor_institution (doctor_id, institution_id),
    KEY idx_doctor_institutions_doctor (doctor_id),
    KEY idx_doctor_institutions_institution (institution_id),
    KEY idx_doctor_institutions_primary (doctor_id, is_primary)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO doctor_institutions (id, doctor_id, institution_id, is_primary)
SELECT UUID(), id, institution_id, 1
FROM doctors
WHERE institution_id IS NOT NULL AND institution_id <> '';

-- ============================================================================
-- Consolidated from V24__remove_institution_district.sql
-- ============================================================================

ALTER TABLE institutions DROP COLUMN district;

-- ============================================================================
-- Consolidated from V25__allow_null_institution_project_original_price.sql
-- ============================================================================

ALTER TABLE institution_projects
    MODIFY COLUMN original_price DECIMAL(10,2) NULL DEFAULT NULL;

-- ============================================================================
-- Consolidated from V26__add_identity_foundation.sql
-- ============================================================================

-- 该迁移面向可重建的测试库。users 是自然人唯一主体，普通用户能力无需额外角色记录。

-- 私有文件只保存存储键；认证材料不得暴露为 /images/** 公共 URL。
CREATE TABLE private_files (
    id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    original_name VARCHAR(255) DEFAULT '',
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_private_files_storage_key (storage_key),
    KEY idx_private_files_owner_purpose (owner_user_id, purpose),
    CONSTRAINT fk_private_files_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每次申请独立留痕；同一身份被驳回或撤销后可以重新申请。
CREATE TABLE identity_applications (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role_code VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    application_data JSON NOT NULL,
    review_note VARCHAR(1000) DEFAULT '',
    reviewed_by VARCHAR(36) DEFAULT NULL,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_identity_applications_user_role (user_id, role_code, submitted_at),
    KEY idx_identity_applications_review_queue (status, submitted_at),
    CONSTRAINT fk_identity_applications_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_identity_applications_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE identity_application_documents (
    application_id VARCHAR(36) NOT NULL,
    file_id VARCHAR(36) NOT NULL,
    document_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (application_id, file_id),
    CONSTRAINT fk_identity_documents_application FOREIGN KEY (application_id) REFERENCES identity_applications(id),
    CONSTRAINT fk_identity_documents_file FOREIGN KEY (file_id) REFERENCES private_files(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 只有审核通过的职业身份进入此表；普通用户 USER 不写入此表。
CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL,
    role_code VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_application_id VARCHAR(36) DEFAULT NULL,
    activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    revoked_by VARCHAR(36) DEFAULT NULL,
    revoke_reason VARCHAR(500) DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_code),
    KEY idx_user_roles_role_status (role_code, status),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_application FOREIGN KEY (source_application_id) REFERENCES identity_applications(id),
    CONSTRAINT fk_user_roles_revoker FOREIGN KEY (revoked_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 医生档案就是用户职业档案：doctors.id = users.id。
ALTER TABLE doctors ADD CONSTRAINT fk_doctors_user FOREIGN KEY (id) REFERENCES users(id);

-- 复用既有医生-机构表作为执业注册关系，避免两套关联数据漂移。
ALTER TABLE doctor_institutions
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER is_primary,
    ADD COLUMN registration_no VARCHAR(100) DEFAULT '' AFTER status,
    ADD COLUMN registration_file_id VARCHAR(36) DEFAULT NULL AFTER registration_no,
    ADD COLUMN confirmed_by VARCHAR(36) DEFAULT NULL AFTER registration_file_id,
    ADD COLUMN confirmed_at TIMESTAMP NULL DEFAULT NULL AFTER confirmed_by,
    ADD COLUMN revoked_at TIMESTAMP NULL DEFAULT NULL AFTER confirmed_at,
    ADD KEY idx_doctor_institutions_status (institution_id, status),
    ADD CONSTRAINT fk_doctor_institutions_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    ADD CONSTRAINT fk_doctor_institutions_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    ADD CONSTRAINT fk_doctor_institutions_file FOREIGN KEY (registration_file_id) REFERENCES private_files(id),
    ADD CONSTRAINT fk_doctor_institutions_confirmer FOREIGN KEY (confirmed_by) REFERENCES users(id);

-- 咨询师、法人、客服与机构的任职关系；医生执业关系统一由 doctor_institutions 表表达。
CREATE TABLE institution_memberships (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    member_role VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    confirmed_by VARCHAR(36) DEFAULT NULL,
    confirmed_at TIMESTAMP NULL DEFAULT NULL,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_institution_membership (user_id, institution_id, member_role),
    KEY idx_institution_memberships_institution_status (institution_id, status),
    CONSTRAINT fk_institution_memberships_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_institution_memberships_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_institution_memberships_confirmer FOREIGN KEY (confirmed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 身份认证、机构确认、平台合作是三个独立层次；合作协议不能压缩成角色上的布尔值。
CREATE TABLE platform_cooperation_agreements (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) DEFAULT NULL,
    institution_id VARCHAR(36) DEFAULT NULL,
    cooperation_role VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    agreement_file_id VARCHAR(36) NOT NULL,
    business_entity_name VARCHAR(200) NOT NULL,
    business_license_no VARCHAR(100) NOT NULL,
    effective_from DATE DEFAULT NULL,
    effective_until DATE DEFAULT NULL,
    approved_by VARCHAR(36) DEFAULT NULL,
    approved_at TIMESTAMP NULL DEFAULT NULL,
    terminated_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_platform_agreements_user (user_id, cooperation_role, status),
    KEY idx_platform_agreements_institution (institution_id, cooperation_role, status),
    CONSTRAINT fk_platform_agreements_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_platform_agreements_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_platform_agreements_file FOREIGN KEY (agreement_file_id) REFERENCES private_files(id),
    CONSTRAINT fk_platform_agreements_approver FOREIGN KEY (approved_by) REFERENCES users(id),
    CONSTRAINT chk_platform_agreement_subject CHECK ((user_id IS NULL) <> (institution_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 身份切换只改变会话上下文，不改变账户或已批准角色。
CREATE TABLE auth_sessions (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    active_role VARCHAR(40) NOT NULL DEFAULT 'USER',
    active_institution_id VARCHAR(36) DEFAULT NULL,
    client_type VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    device_name VARCHAR(200) DEFAULT '',
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_sessions_refresh_token_hash (refresh_token_hash),
    KEY idx_auth_sessions_user_active (user_id, revoked_at, expires_at),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_auth_sessions_institution FOREIGN KEY (active_institution_id) REFERENCES institutions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 医生只能提交加入、退出和个人服务资料变更；机构确认后才写入正式医生-机构项目关系。
CREATE TABLE doctor_project_change_requests (
    id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    request_type VARCHAR(30) NOT NULL,
    service_description TEXT,
    service_tags VARCHAR(500) DEFAULT '',
    schedule_note VARCHAR(500) DEFAULT '',
    cover_image VARCHAR(500) DEFAULT '',
    images VARCHAR(2000) DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitted_by VARCHAR(36) NOT NULL,
    reviewed_by VARCHAR(36) DEFAULT NULL,
    review_note VARCHAR(1000) DEFAULT '',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    pending_key VARCHAR(73) GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN CONCAT(doctor_id, ':', institution_project_id) ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dpcr_single_pending (pending_key),
    KEY idx_dpcr_doctor_status (doctor_id, status, submitted_at),
    KEY idx_dpcr_institution_status (institution_id, status, submitted_at),
    KEY idx_dpcr_project_status (institution_project_id, status),
    CONSTRAINT fk_dpcr_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    CONSTRAINT fk_dpcr_institution FOREIGN KEY (institution_id) REFERENCES institutions(id),
    CONSTRAINT fk_dpcr_institution_project FOREIGN KEY (institution_project_id) REFERENCES institution_projects(id),
    CONSTRAINT fk_dpcr_submitter FOREIGN KEY (submitted_by) REFERENCES users(id),
    CONSTRAINT fk_dpcr_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT chk_dpcr_type CHECK (request_type IN ('JOIN', 'PROFILE_UPDATE', 'LEAVE')),
    CONSTRAINT chk_dpcr_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 分账生效值与协商提案分表保存；任何一方的新提案都不会覆盖当前生效配置。
CREATE TABLE split_config_proposals (
    id VARCHAR(36) NOT NULL,
    config_id VARCHAR(36) DEFAULT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    institution_project_id VARCHAR(36) NOT NULL,
    consultation_fee DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    commission_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    institution_rate DECIMAL(5,2) NOT NULL DEFAULT 40.00,
    proposer_user_id VARCHAR(36) NOT NULL,
    proposer_side VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    doctor_confirmed_at TIMESTAMP NULL DEFAULT NULL,
    institution_confirmed_at TIMESTAMP NULL DEFAULT NULL,
    decided_by VARCHAR(36) DEFAULT NULL,
    decision_note VARCHAR(1000) DEFAULT '',
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMP NULL DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    pending_key VARCHAR(73) GENERATED ALWAYS AS (
        CASE WHEN status = 'PENDING' THEN CONCAT(doctor_id, ':', institution_project_id) ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_split_proposal_single_pending (pending_key),
    KEY idx_split_proposal_doctor_status (doctor_id, status, submitted_at),
    KEY idx_split_proposal_project_status (institution_project_id, status, submitted_at),
    CONSTRAINT fk_split_proposal_config FOREIGN KEY (config_id) REFERENCES doctor_institution_project_configs(id),
    CONSTRAINT fk_split_proposal_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    CONSTRAINT fk_split_proposal_project FOREIGN KEY (institution_project_id) REFERENCES institution_projects(id),
    CONSTRAINT fk_split_proposal_proposer FOREIGN KEY (proposer_user_id) REFERENCES users(id),
    CONSTRAINT fk_split_proposal_decider FOREIGN KEY (decided_by) REFERENCES users(id),
    CONSTRAINT chk_split_proposer_side CHECK (proposer_side IN ('DOCTOR', 'INSTITUTION')),
    CONSTRAINT chk_split_proposal_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE doctor_projects
    ADD CONSTRAINT fk_doctor_projects_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(id),
    ADD CONSTRAINT fk_doctor_projects_project FOREIGN KEY (project_id) REFERENCES projects(id),
    ADD CONSTRAINT fk_doctor_projects_institution_project FOREIGN KEY (institution_project_id) REFERENCES institution_projects(id);

-- ============================================================================
-- Consolidated payment foundation (formerly V27__payment_foundation.sql)
-- ============================================================================

-- Real-payment persistence foundation.
--
-- This migration deliberately keeps the existing table names so the current
-- demo application can continue to run while real providers are introduced.
-- New payment attempts can coexist for the same order/payment stage; provider
-- callbacks and provider-side refunds are recorded independently and idempotently.

-- ---------------------------------------------------------------------------
-- Orders: immutable money/currency snapshot used to create and verify payments.
-- ---------------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER cover_image,
    ADD COLUMN total_amount_minor BIGINT NULL AFTER price,
    ADD COLUMN paid_amount_minor BIGINT NULL AFTER paid_amount,
    ADD COLUMN consultation_fee_minor BIGINT NULL AFTER consultation_fee,
    ADD COLUMN discount_amount_minor BIGINT NULL AFTER discount_amount,
    ADD COLUMN remaining_amount_minor BIGINT NULL AFTER remaining_amount,
    ADD COLUMN pricing_country CHAR(2) NULL AFTER remaining_amount_minor;

UPDATE orders
SET total_amount_minor = ROUND(price * 100),
    paid_amount_minor = ROUND(paid_amount * 100),
    consultation_fee_minor = ROUND(consultation_fee * 100),
    discount_amount_minor = ROUND(discount_amount * 100),
    remaining_amount_minor = ROUND(remaining_amount * 100)
WHERE total_amount_minor IS NULL
   OR paid_amount_minor IS NULL
   OR consultation_fee_minor IS NULL
   OR discount_amount_minor IS NULL
   OR remaining_amount_minor IS NULL;

-- Keep the new snapshot columns nullable during the compatibility window. The
-- existing application does not dual-write them yet. A later migration must
-- make them NOT NULL after all writers have moved to minor-unit amounts.
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_money_non_negative CHECK (
        total_amount_minor >= 0
        AND paid_amount_minor >= 0
        AND consultation_fee_minor >= 0
        AND discount_amount_minor >= 0
        AND remaining_amount_minor >= 0
    );

-- ---------------------------------------------------------------------------
-- Payments: one row is one provider attempt, not one order/payment stage.
-- Keep amount/method/transaction_id temporarily for old API compatibility.
-- ---------------------------------------------------------------------------
ALTER TABLE payments
    DROP INDEX uk_payments_order_type,
    MODIFY COLUMN amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN provider VARCHAR(30) NOT NULL DEFAULT 'DEMO' AFTER payment_type,
    ADD COLUMN payment_method VARCHAR(50) NULL AFTER provider,
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER payment_method,
    ADD COLUMN amount_minor BIGINT NULL AFTER currency,
    ADD COLUMN provider_payment_id VARCHAR(150) NULL AFTER transaction_id,
    ADD COLUMN provider_transaction_id VARCHAR(150) NULL AFTER provider_payment_id,
    ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER provider_transaction_id,
    ADD COLUMN failure_code VARCHAR(100) NULL AFTER status,
    ADD COLUMN failure_message VARCHAR(500) NULL AFTER failure_code,
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0 AFTER amount_minor,
    ADD COLUMN authorized_at DATETIME NULL AFTER refunded_amount_minor,
    ADD COLUMN cancelled_at DATETIME NULL AFTER authorized_at,
    ADD COLUMN expires_at DATETIME NULL AFTER cancelled_at,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER expires_at;

UPDATE payments
SET amount_minor = ROUND(amount * 100),
    payment_method = CASE
        WHEN payment_method IS NULL OR payment_method = '' THEN method
        ELSE payment_method
    END,
    provider_transaction_id = CASE
        WHEN provider_transaction_id IS NULL OR provider_transaction_id = '' THEN NULLIF(transaction_id, '')
        ELSE provider_transaction_id
    END
WHERE amount_minor IS NULL;

-- amount_minor remains nullable until the provider-aware application service
-- is deployed. CHECK evaluates to UNKNOWN for legacy-compatible null values.
ALTER TABLE payments
    ADD UNIQUE KEY uk_payments_provider_payment (provider, provider_payment_id),
    ADD UNIQUE KEY uk_payments_user_idempotency (user_id, idempotency_key),
    ADD KEY idx_payments_order_type_status (order_id, payment_type, status),
    ADD KEY idx_payments_status_updated (status, updated_at),
    ADD KEY idx_payments_provider_transaction (provider, provider_transaction_id),
    ADD CONSTRAINT chk_payments_amount_positive CHECK (amount_minor > 0),
    ADD CONSTRAINT chk_payments_refunded_amount CHECK (
        refunded_amount_minor >= 0 AND refunded_amount_minor <= amount_minor
    );

-- ---------------------------------------------------------------------------
-- Provider callbacks: immutable inbox with provider event id as the dedupe key.
-- The raw payload is retained for audit/replay; application logging must redact
-- secrets and payment credentials before data reaches this table.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_events (
    id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NULL,
    provider VARCHAR(30) NOT NULL,
    provider_event_id VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    signature_valid TINYINT(1) NOT NULL DEFAULT 0,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_events_provider_event (provider, provider_event_id),
    KEY idx_payment_events_payment (payment_id),
    KEY idx_payment_events_processing (processing_status, received_at),
    CONSTRAINT fk_payment_events_payment
        FOREIGN KEY (payment_id) REFERENCES payments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Refund case: business review and provider execution are separate states.
-- Existing display snapshot fields remain available to the admin application.
-- ---------------------------------------------------------------------------
ALTER TABLE refunds
    MODIFY COLUMN amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    MODIFY COLUMN refund_amount DECIMAL(19,4) DEFAULT 0,
    MODIFY COLUMN payment_amount DECIMAL(19,4) DEFAULT 0,
    ADD COLUMN refund_no VARCHAR(50) NULL AFTER id,
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER user_id,
    ADD COLUMN requested_amount_minor BIGINT NULL AFTER amount,
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0 AFTER requested_amount_minor,
    ADD COLUMN reason_code VARCHAR(50) NULL AFTER reason,
    ADD COLUMN reviewed_by VARCHAR(36) NULL AFTER original_status,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
    ADD COLUMN reject_reason VARCHAR(500) NULL AFTER reviewed_at,
    ADD COLUMN requested_at DATETIME NULL AFTER reject_reason,
    ADD COLUMN completed_at DATETIME NULL AFTER requested_at;

UPDATE refunds
SET refund_no = CONCAT('LEGACY-', id),
    requested_amount_minor = ROUND(amount * 100),
    refunded_amount_minor = CASE
        WHEN status = 'APPROVED' THEN ROUND(amount * 100)
        ELSE 0
    END,
    reason_code = 'LEGACY',
    requested_at = created_at,
    completed_at = CASE WHEN status = 'APPROVED' THEN processed_at ELSE NULL END
WHERE refund_no IS NULL OR requested_amount_minor IS NULL;

ALTER TABLE refunds
    DROP INDEX uk_refunds_single_active,
    DROP COLUMN active_order_id,
    ADD COLUMN active_order_id VARCHAR(36) GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('PENDING', 'PENDING_REVIEW', 'APPROVED', 'REFUND_PROCESSING')
                THEN order_id
            ELSE NULL
        END
    ) STORED AFTER status,
    ADD UNIQUE KEY uk_refunds_refund_no (refund_no),
    ADD UNIQUE KEY uk_refunds_single_active (active_order_id),
    ADD KEY idx_refunds_user_status (user_id, status),
    ADD CONSTRAINT chk_refunds_requested_amount CHECK (requested_amount_minor > 0),
    ADD CONSTRAINT chk_refunds_refunded_amount CHECK (
        refunded_amount_minor >= 0
        AND refunded_amount_minor <= requested_amount_minor
    );

-- One business refund can execute against consultation and balance payments
-- separately, possibly through different providers.
CREATE TABLE refund_items (
    id VARCHAR(36) NOT NULL,
    refund_id VARCHAR(36) NOT NULL,
    payment_id VARCHAR(36) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    provider_refund_id VARCHAR(150) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    failure_code VARCHAR(100) NULL,
    failure_message VARCHAR(500) NULL,
    requested_at DATETIME NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_items_provider_refund (provider, provider_refund_id),
    KEY idx_refund_items_refund (refund_id),
    KEY idx_refund_items_payment (payment_id),
    KEY idx_refund_items_status_updated (status, updated_at),
    CONSTRAINT fk_refund_items_refund
        FOREIGN KEY (refund_id) REFERENCES refunds(id),
    CONSTRAINT fk_refund_items_payment
        FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT chk_refund_items_amount_positive CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Settlements: preserve the internal split calculation while adding the money
-- snapshot and provider transfer lifecycle needed for real payouts.
-- ---------------------------------------------------------------------------
ALTER TABLE settlements
    MODIFY COLUMN total_amount DECIMAL(19,4) NOT NULL,
    MODIFY COLUMN platform_amount DECIMAL(19,4) NOT NULL,
    MODIFY COLUMN institution_amount DECIMAL(19,4) NOT NULL,
    MODIFY COLUMN consultant_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    MODIFY COLUMN doctor_amount DECIMAL(19,4) NOT NULL,
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER order_id,
    ADD COLUMN total_amount_minor BIGINT NULL AFTER total_amount,
    ADD COLUMN platform_amount_minor BIGINT NULL AFTER platform_amount,
    ADD COLUMN institution_amount_minor BIGINT NULL AFTER institution_amount,
    ADD COLUMN consultant_amount_minor BIGINT NULL AFTER consultant_amount,
    ADD COLUMN doctor_amount_minor BIGINT NULL AFTER doctor_amount,
    ADD COLUMN payout_provider VARCHAR(30) NULL AFTER doctor_rate,
    ADD COLUMN provider_transfer_id VARCHAR(150) NULL AFTER payout_provider,
    ADD COLUMN failure_code VARCHAR(100) NULL AFTER provider_transfer_id,
    ADD COLUMN failure_message VARCHAR(500) NULL AFTER failure_code;

UPDATE settlements
SET total_amount_minor = ROUND(total_amount * 100),
    platform_amount_minor = ROUND(platform_amount * 100),
    institution_amount_minor = ROUND(institution_amount * 100),
    consultant_amount_minor = ROUND(consultant_amount * 100),
    doctor_amount_minor = ROUND(doctor_amount * 100)
WHERE total_amount_minor IS NULL
   OR platform_amount_minor IS NULL
   OR institution_amount_minor IS NULL
   OR consultant_amount_minor IS NULL
   OR doctor_amount_minor IS NULL;

-- As with orders/payments, keep minor-unit columns nullable until settlement
-- writers dual-write them, then harden them in a separate deployment.
ALTER TABLE settlements
    ADD UNIQUE KEY uk_settlements_provider_transfer (payout_provider, provider_transfer_id),
    ADD CONSTRAINT chk_settlements_money_non_negative CHECK (
        total_amount_minor >= 0
        AND platform_amount_minor >= 0
        AND institution_amount_minor >= 0
        AND consultant_amount_minor >= 0
        AND doctor_amount_minor >= 0
    ),
    ADD CONSTRAINT chk_settlements_split_total CHECK (
        platform_amount_minor
        + institution_amount_minor
        + consultant_amount_minor
        + doctor_amount_minor
        = total_amount_minor
    );
