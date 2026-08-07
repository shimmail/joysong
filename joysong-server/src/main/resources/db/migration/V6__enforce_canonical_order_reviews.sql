-- 每个订单只允许一条未删除的主评价（target_type = INSTITUTION）。
-- 兼容历史 PROJECT / DOCTOR 派生评价；这些记录不参与新的订单评价统计。

UPDATE reviews r
JOIN reviews newer
  ON newer.order_id = r.order_id
 AND newer.target_type = 'INSTITUTION'
 AND newer.deleted_at IS NULL
 AND (
      newer.created_at > r.created_at
      OR (newer.created_at = r.created_at AND newer.id > r.id)
 )
SET r.deleted_at = CURRENT_TIMESTAMP
WHERE r.target_type = 'INSTITUTION'
  AND r.deleted_at IS NULL;

ALTER TABLE reviews
    ADD COLUMN active_canonical_order_id VARCHAR(36)
        GENERATED ALWAYS AS (
            CASE
                WHEN deleted_at IS NULL AND target_type = 'INSTITUTION' THEN order_id
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_reviews_active_canonical_order (active_canonical_order_id),
    ADD KEY idx_reviews_doctor_type (doctor_id, target_type);

-- 以订单主评价为唯一统计口径，校准历史手工填写或旧三份派生评价造成的聚合偏差。
-- case_count 表示平台维护的真实案例量，不等同于评价量，本迁移不修改它。
UPDATE institutions i
LEFT JOIN (
    SELECT target_id AS institution_id,
           COUNT(*) AS review_count,
           ROUND(AVG(rating), 1) AS rating
    FROM reviews
    WHERE deleted_at IS NULL
      AND target_type = 'INSTITUTION'
    GROUP BY target_id
) stats ON stats.institution_id = i.id
SET i.review_count = COALESCE(stats.review_count, 0),
    i.rating = COALESCE(stats.rating, 0.0)
WHERE i.deleted_at IS NULL;

UPDATE doctors d
LEFT JOIN (
    SELECT doctor_id,
           COUNT(*) AS review_count,
           ROUND(AVG(rating), 1) AS rating
    FROM reviews
    WHERE deleted_at IS NULL
      AND target_type = 'INSTITUTION'
      AND doctor_id <> ''
    GROUP BY doctor_id
) stats ON stats.doctor_id = d.id
SET d.review_count = COALESCE(stats.review_count, 0),
    d.rating = COALESCE(stats.rating, 0.0)
WHERE d.deleted_at IS NULL;

UPDATE institution_projects ip
LEFT JOIN (
    SELECT o.institution_project_id,
           COUNT(*) AS review_count,
           ROUND(AVG(r.rating), 1) AS rating
    FROM reviews r
    JOIN orders o ON o.id = r.order_id
    WHERE r.deleted_at IS NULL
      AND r.target_type = 'INSTITUTION'
      AND o.deleted_at IS NULL
      AND o.institution_project_id <> ''
    GROUP BY o.institution_project_id
) stats ON stats.institution_project_id = ip.id
SET ip.review_count = COALESCE(stats.review_count, 0),
    ip.rating = COALESCE(stats.rating, 0.0)
WHERE ip.deleted_at IS NULL;
