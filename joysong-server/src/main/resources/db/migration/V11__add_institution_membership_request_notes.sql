ALTER TABLE doctor_institutions
    ADD COLUMN request_note VARCHAR(1000) NOT NULL DEFAULT '' AFTER status,
    ADD COLUMN review_note VARCHAR(1000) NOT NULL DEFAULT '' AFTER request_note;

ALTER TABLE institution_memberships
    ADD COLUMN request_note VARCHAR(1000) NOT NULL DEFAULT '' AFTER status,
    ADD COLUMN review_note VARCHAR(1000) NOT NULL DEFAULT '' AFTER request_note;
