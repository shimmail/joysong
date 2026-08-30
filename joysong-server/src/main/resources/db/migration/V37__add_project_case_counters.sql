ALTER TABLE `projects`
    ADD COLUMN `case_count` INT NOT NULL DEFAULT 0 AFTER `review_count`,
    ADD CONSTRAINT `chk_projects_case_count` CHECK (`case_count` >= 0);

ALTER TABLE `institution_projects`
    ADD COLUMN `case_count` INT NOT NULL DEFAULT 0 AFTER `review_count`,
    ADD CONSTRAINT `chk_institution_projects_case_count` CHECK (`case_count` >= 0);
