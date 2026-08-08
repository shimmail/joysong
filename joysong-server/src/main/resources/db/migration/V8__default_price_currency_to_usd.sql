-- Catalog prices now carry an explicit currency. Existing transactional currency values
-- are preserved; only defaults for newly inserted rows are changed.
ALTER TABLE projects
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER reference_price;

ALTER TABLE institution_projects
    ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD' AFTER original_price;

ALTER TABLE orders MODIFY COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE payments MODIFY COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE refunds MODIFY COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE settlements MODIFY COLUMN currency CHAR(3) NOT NULL DEFAULT 'USD';
