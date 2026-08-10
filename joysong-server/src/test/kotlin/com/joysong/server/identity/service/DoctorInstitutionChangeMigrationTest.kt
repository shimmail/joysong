package com.joysong.server.identity.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoctorInstitutionChangeMigrationTest {

    @Test
    fun `V13 migration declares the doctor institution request ledger contract`() {
        val migration = requireNotNull(
            javaClass.getResource("/db/migration/V13__add_doctor_institution_change_requests.sql")
        ).readText()
        val normalized = migration.replace(Regex("\\s+"), " ").trim()

        assertContains(normalized, "CREATE TABLE doctor_institution_change_requests")
        assertContains(normalized, "action VARCHAR(20) NOT NULL")
        assertContains(normalized, "status VARCHAR(20) NOT NULL DEFAULT 'PENDING'")
        assertContains(normalized, "action IN ('JOIN', 'LEAVE')")
        assertContains(normalized, "status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')")
        assertContains(normalized, "CASE WHEN status = 'PENDING' THEN CONCAT(doctor_id, ':', institution_id) ELSE NULL END")
        assertContains(normalized, "UNIQUE KEY uk_doctor_institution_change_requests_pending (pending_key)")

        assertContains(normalized, "INSERT INTO doctor_institution_change_requests")
        assertContains(normalized, "FROM doctor_institutions")
        assertContains(normalized, "WHEN status = 'CHANGES_REQUESTED' THEN 'REJECTED'")
        assertContains(normalized, "WHEN status = 'REVOKED' THEN 'APPROVED'")
        assertContains(normalized, "DELETE FROM doctor_institutions WHERE status NOT IN ('APPROVED', 'REVOKED')")
        assertContains(normalized, "UPDATE institution_memberships SET status = 'REJECTED' WHERE status = 'CHANGES_REQUESTED'")

        listOf(
            "FOREIGN KEY (doctor_id) REFERENCES doctors(id)",
            "FOREIGN KEY (institution_id) REFERENCES institutions(id)",
            "FOREIGN KEY (submitted_by) REFERENCES users(id)",
            "FOREIGN KEY (reviewed_by) REFERENCES users(id)"
        ).forEach { assertContains(normalized, it) }

        listOf(
            "CONSTRAINT chk_doctor_institution_change_requests_action CHECK (action IN ('JOIN', 'LEAVE'))",
            "CONSTRAINT chk_doctor_institution_change_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))",
            "CONSTRAINT chk_doctor_institution_change_requests_review_note CHECK (status <> 'REJECTED' OR review_note <> '')",
            "CONSTRAINT chk_doctor_institutions_status CHECK (status IN ('APPROVED', 'REVOKED'))",
            "CONSTRAINT chk_institution_memberships_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'))"
        ).forEach { assertContains(normalized, it) }
    }

    private fun assertContains(migration: String, contract: String) {
        assertTrue(migration.contains(contract), "Migration must contain: $contract")
    }
}
