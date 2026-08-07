-- Supports the homepage query: active institution projects ordered by sales.
CREATE INDEX idx_ip_active_sales_count ON institution_projects (is_active, sales_count DESC);

-- Supports the newest expert-article query used on the homepage.
CREATE INDEX idx_articles_publish_date ON expert_articles (publish_date DESC);
