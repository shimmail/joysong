-- Some databases applied an earlier merged V12 that did not add this column.
-- Reconcile those schemas without changing databases where the current V12 ran.
SET @add_doctor_project_price = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE doctor_projects ADD COLUMN price DECIMAL(10, 2) NULL AFTER institution_project_id',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'doctor_projects'
      AND column_name = 'price'
);
PREPARE add_doctor_project_price_stmt FROM @add_doctor_project_price;
EXECUTE add_doctor_project_price_stmt;
DEALLOCATE PREPARE add_doctor_project_price_stmt;

UPDATE doctor_projects dp
JOIN institution_projects ip ON ip.id = dp.institution_project_id
SET dp.price = ip.price
WHERE dp.price IS NULL;

UPDATE doctor_projects
SET price = 0
WHERE price IS NULL;

ALTER TABLE doctor_projects
    MODIFY COLUMN price DECIMAL(10, 2) NOT NULL;

SET @add_doctor_project_price_check = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE doctor_projects ADD CONSTRAINT chk_doctor_projects_price CHECK (price >= 0)',
        'SELECT 1'
    )
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'doctor_projects'
      AND constraint_name = 'chk_doctor_projects_price'
      AND constraint_type = 'CHECK'
);
PREPARE add_doctor_project_price_check_stmt FROM @add_doctor_project_price_check;
EXECUTE add_doctor_project_price_check_stmt;
DEALLOCATE PREPARE add_doctor_project_price_check_stmt;

