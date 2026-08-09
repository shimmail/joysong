package com.joysong.server.common.initializer

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(4)
class IdentityDataInitializer(
    private val jdbcTemplate: JdbcTemplate
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        seedPrivateFiles()
        seedApplications()
        seedRoles()
        seedInstitutionMemberships()
        seedCooperationAgreements()
        approveSeedDoctorPractices()
        seedProjectCollaboration()
    }

    private fun seedPrivateFiles() {
        upsertFile(SeedIds.ID_CARD_FILE_ID, SeedIds.DOC_ID_1, "IDENTITY_CARD", "identity/doctor-1/id-card.enc", "身份证.jpg", "image/jpeg")
        upsertFile(SeedIds.DOCTOR_CERT_FILE_ID, SeedIds.DOC_ID_1, "DOCTOR_QUALIFICATION", "identity/doctor-1/qualification.enc", "执业医师资格证.jpg", "image/jpeg")
        upsertFile(SeedIds.DOCTOR_AGREEMENT_FILE_ID, SeedIds.DOC_ID_1, "COOPERATION_AGREEMENT", "agreements/doctor-1.pdf.enc", "医生合作协议.pdf", "application/pdf")
        upsertFile(SeedIds.INSTITUTION_AGREEMENT_FILE_ID, SeedIds.LEGAL_REP_ID, "COOPERATION_AGREEMENT", "agreements/institution-1.pdf.enc", "机构合作协议.pdf", "application/pdf")
        upsertFile(SeedIds.PENDING_ID_CARD_FILE_ID, SeedIds.USER_ID_2, "IDENTITY_CARD", "identity/user-2/id-card.enc", "身份证待审核.jpg", "image/jpeg")
        upsertFile(SeedIds.REJECTED_DOCTOR_CERT_FILE_ID, SeedIds.USER_ID_3, "DOCTOR_QUALIFICATION", "identity/user-3/invalid-cert.enc", "资格证待补充.jpg", "image/jpeg")
    }

    private fun upsertFile(id: String, ownerId: String, purpose: String, storageKey: String, originalName: String, contentType: String) {
        jdbcTemplate.update(
            """
            INSERT INTO private_files
                (id, owner_user_id, purpose, storage_key, original_name, content_type, size_bytes, sha256, status)
            VALUES (?, ?, ?, ?, ?, ?, 1024, REPEAT('0', 64), 'ACTIVE')
            ON DUPLICATE KEY UPDATE owner_user_id = VALUES(owner_user_id), purpose = VALUES(purpose),
                storage_key = VALUES(storage_key), original_name = VALUES(original_name),
                content_type = VALUES(content_type), status = 'ACTIVE', deleted_at = NULL
            """.trimIndent(),
            id, ownerId, purpose, storageKey, originalName, contentType
        )
    }

    private fun seedApplications() {
        upsertApplication(
            SeedIds.APPROVED_DOCTOR_APPLICATION_ID, SeedIds.DOC_ID_1, "DOCTOR", "APPROVED",
            """{"realName":"王医生","qualificationNo":"TEST-DOC-001"}""", "测试医生认证已通过", SeedIds.ADMIN_ID
        )
        upsertApplication(
            SeedIds.PENDING_CONSULTANT_APPLICATION_ID, SeedIds.USER_ID_2, "CONSULTANT", "PENDING",
            """{"realName":"小红","institutionId":"${SeedIds.INST_ID_1}"}""", "", null
        )
        upsertApplication(
            SeedIds.REJECTED_DOCTOR_APPLICATION_ID, SeedIds.USER_ID_3, "DOCTOR", "REJECTED",
            """{"realName":"小丽","qualificationNo":"INCOMPLETE"}""", "执业注册证明不完整，请补充后重新提交", SeedIds.ADMIN_ID
        )

        linkDocument(SeedIds.APPROVED_DOCTOR_APPLICATION_ID, SeedIds.ID_CARD_FILE_ID, "ID_CARD_FRONT")
        linkDocument(SeedIds.APPROVED_DOCTOR_APPLICATION_ID, SeedIds.DOCTOR_CERT_FILE_ID, "DOCTOR_QUALIFICATION")
        linkDocument(SeedIds.PENDING_CONSULTANT_APPLICATION_ID, SeedIds.PENDING_ID_CARD_FILE_ID, "ID_CARD_FRONT")
        linkDocument(SeedIds.REJECTED_DOCTOR_APPLICATION_ID, SeedIds.REJECTED_DOCTOR_CERT_FILE_ID, "DOCTOR_QUALIFICATION")
    }

    private fun upsertApplication(id: String, userId: String, role: String, status: String, data: String, note: String, reviewerId: String?) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_applications
                (id, user_id, role_code, status, application_data, review_note, reviewed_by, reviewed_at)
            VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, IF(? IS NULL, NULL, CURRENT_TIMESTAMP))
            ON DUPLICATE KEY UPDATE status = VALUES(status), application_data = VALUES(application_data),
                review_note = VALUES(review_note), reviewed_by = VALUES(reviewed_by), reviewed_at = VALUES(reviewed_at)
            """.trimIndent(),
            id, userId, role, status, data, note, reviewerId, reviewerId
        )
    }

    private fun linkDocument(applicationId: String, fileId: String, documentType: String) {
        jdbcTemplate.update(
            """
            INSERT INTO identity_application_documents (application_id, file_id, document_type)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE document_type = VALUES(document_type)
            """.trimIndent(),
            applicationId, fileId, documentType
        )
    }

    private fun seedRoles() {
        listOf(SeedIds.DOC_ID_1, SeedIds.DOC_ID_2, SeedIds.DOC_ID_3, SeedIds.DOC_ID_4, SeedIds.DOC_ID_5, SeedIds.DOC_ID_6)
            .forEach { doctorId ->
                upsertRole(
                    doctorId,
                    "DOCTOR",
                    if (doctorId == SeedIds.DOC_ID_1) SeedIds.APPROVED_DOCTOR_APPLICATION_ID else null
                )
            }
        upsertRole(SeedIds.DOC_ID_1, "CONSULTANT", null)
        upsertRole(SeedIds.DOC_ID_1, "INSTITUTION_LEGAL_REPRESENTATIVE", null)
        upsertRole(SeedIds.CONSULTANT_ID, "CONSULTANT", null)
        upsertRole(SeedIds.LEGAL_REP_ID, "INSTITUTION_LEGAL_REPRESENTATIVE", null)
        upsertRole(SeedIds.CS_USER_ID, "INSTITUTION_CUSTOMER_SERVICE", null)
    }

    private fun upsertRole(userId: String, role: String, sourceApplicationId: String?) {
        jdbcTemplate.update(
            """
            INSERT INTO user_roles (user_id, role_code, status, source_application_id)
            VALUES (?, ?, 'ACTIVE', ?)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', source_application_id = VALUES(source_application_id),
                revoked_at = NULL, revoked_by = NULL, revoke_reason = ''
            """.trimIndent(),
            userId, role, sourceApplicationId
        )
    }

    private fun seedInstitutionMemberships() {
        upsertMembership("93000001-0000-4000-8000-000000000001", SeedIds.CONSULTANT_ID, SeedIds.INST_ID_1, "CONSULTANT")
        upsertMembership("93000001-0000-4000-8000-000000000006", SeedIds.CONSULTANT_ID, SeedIds.INST_ID_2, "CONSULTANT")
        upsertMembership("93000001-0000-4000-8000-000000000007", SeedIds.CONSULTANT_ID, SeedIds.INST_ID_3, "CONSULTANT")
        upsertMembership("93000001-0000-4000-8000-000000000008", SeedIds.CONSULTANT_ID, SeedIds.INST_ID_4, "CONSULTANT")
        upsertMembership("93000001-0000-4000-8000-000000000002", SeedIds.DOC_ID_1, SeedIds.INST_ID_1, "CONSULTANT")
        upsertMembership("93000001-0000-4000-8000-000000000003", SeedIds.DOC_ID_1, SeedIds.INST_ID_1, "INSTITUTION_LEGAL_REPRESENTATIVE")
        upsertMembership("93000001-0000-4000-8000-000000000004", SeedIds.LEGAL_REP_ID, SeedIds.INST_ID_2, "INSTITUTION_LEGAL_REPRESENTATIVE")
        upsertMembership("93000001-0000-4000-8000-000000000005", SeedIds.CS_USER_ID, SeedIds.INST_ID_1, "INSTITUTION_CUSTOMER_SERVICE")
    }

    private fun upsertMembership(id: String, userId: String, institutionId: String, memberRole: String) {
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, ?, 'APPROVED', ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE status = 'APPROVED', confirmed_by = VALUES(confirmed_by),
                confirmed_at = CURRENT_TIMESTAMP, revoked_at = NULL
            """.trimIndent(),
            id, userId, institutionId, memberRole, SeedIds.ADMIN_ID
        )
    }

    private fun seedCooperationAgreements() {
        upsertUserAgreement(
            "94000001-0000-4000-8000-000000000001", SeedIds.DOC_ID_1, "DOCTOR",
            SeedIds.DOCTOR_AGREEMENT_FILE_ID, "王医生个人工作室", "TEST-BIZ-DOCTOR-001"
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_cooperation_agreements
                (id, institution_id, cooperation_role, status, agreement_file_id, business_entity_name,
                 business_license_no, effective_from, approved_by, approved_at)
            VALUES (?, ?, 'INSTITUTION', 'ACTIVE', ?, '上海娇颜颂医美中心', 'TEST-BIZ-INST-001', CURRENT_DATE, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', agreement_file_id = VALUES(agreement_file_id),
                approved_by = VALUES(approved_by), approved_at = CURRENT_TIMESTAMP, terminated_at = NULL
            """.trimIndent(),
            "94000001-0000-4000-8000-000000000002", SeedIds.INST_ID_1,
            SeedIds.INSTITUTION_AGREEMENT_FILE_ID, SeedIds.ADMIN_ID
        )
    }

    private fun upsertUserAgreement(id: String, userId: String, role: String, fileId: String, entityName: String, licenseNo: String) {
        jdbcTemplate.update(
            """
            INSERT INTO platform_cooperation_agreements
                (id, user_id, cooperation_role, status, agreement_file_id, business_entity_name,
                 business_license_no, effective_from, approved_by, approved_at)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, CURRENT_DATE, ?, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', agreement_file_id = VALUES(agreement_file_id),
                approved_by = VALUES(approved_by), approved_at = CURRENT_TIMESTAMP, terminated_at = NULL
            """.trimIndent(),
            id, userId, role, fileId, entityName, licenseNo, SeedIds.ADMIN_ID
        )
    }

    private fun approveSeedDoctorPractices() {
        jdbcTemplate.update(
            """
            UPDATE doctor_institutions
            SET status = 'APPROVED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP, revoked_at = NULL
            WHERE doctor_id IN (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            SeedIds.ADMIN_ID,
            SeedIds.DOC_ID_1, SeedIds.DOC_ID_2, SeedIds.DOC_ID_3,
            SeedIds.DOC_ID_4, SeedIds.DOC_ID_5, SeedIds.DOC_ID_6
        )
    }

    /** 为医生申请、机构审核和双方分账确认提供可直接操作的测试记录。 */
    private fun seedProjectCollaboration() {
        jdbcTemplate.update(
            """
            INSERT IGNORE INTO doctor_project_change_requests
                (id, doctor_id, institution_id, institution_project_id, request_type,
                 service_description, service_tags, schedule_note, status, submitted_by)
            VALUES (?, ?, ?, ?, 'PROFILE_UPDATE',
                    '专注眼部年轻化方案，申请更新个人项目介绍。', '眼部整形,面部年轻化',
                    '每周二、周四下午出诊', 'PENDING', ?)
            """.trimIndent(),
            SeedIds.PROJECT_CHANGE_REQUEST_ID,
            SeedIds.DOC_ID_2,
            SeedIds.INST_ID_1,
            SeedIds.IP_ID_4,
            SeedIds.DOC_ID_2
        )
        jdbcTemplate.update(
            """
            INSERT IGNORE INTO doctor_institution_project_configs
                (id, doctor_id, institution_project_id, consultation_fee, commission_rate, institution_rate)
            VALUES (?, ?, ?, 25.00, 10.00, 40.00)
            """.trimIndent(),
            SeedIds.SPLIT_CONFIG_ID,
            SeedIds.DOC_ID_1,
            SeedIds.IP_ID_1
        )
        jdbcTemplate.update(
            """
            INSERT IGNORE INTO split_config_proposals
                (id, config_id, doctor_id, institution_project_id, consultation_fee,
                 commission_rate, institution_rate, proposer_user_id, proposer_side,
                 status, doctor_confirmed_at)
            VALUES (?, ?, ?, ?, 30.00, 12.00, 40.00, ?, 'DOCTOR', 'PENDING', NOW())
            """.trimIndent(),
            SeedIds.SPLIT_PROPOSAL_ID,
            SeedIds.SPLIT_CONFIG_ID,
            SeedIds.DOC_ID_1,
            SeedIds.IP_ID_1,
            SeedIds.DOC_ID_1
        )
    }
}
