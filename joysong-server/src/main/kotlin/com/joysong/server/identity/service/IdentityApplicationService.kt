package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.user.service.AccountLifecycleGuard
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

private val SELF_SERVICE_ROLES = setOf(
    "DOCTOR",
    "CONSULTANT",
    "INSTITUTION_LEGAL_REPRESENTATIVE"
)

private val REQUIRED_DOCUMENTS = mapOf(
    "INSTITUTION_LEGAL_REPRESENTATIVE" to setOf("BUSINESS_LICENSE", "ID_CARD_FRONT", "ID_CARD_BACK"),
    "DOCTOR" to setOf(
        "ID_CARD_FRONT",
        "ID_CARD_BACK",
        "ID_CARD_HANDHELD",
        "DOCTOR_QUALIFICATION",
        "DOCTOR_PRACTICE_CERTIFICATE"
    ),
    "CONSULTANT" to setOf("ID_CARD_FRONT", "ID_CARD_BACK", "CONSULTANT_PROOF")
)

@Service
class IdentityApplicationService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val accountLifecycleGuard: AccountLifecycleGuard,
) {
    fun overview(userId: String): IdentityOverviewView {
        val roles = jdbcTemplate.query(
            "SELECT role_code, status, activated_at, revoked_at FROM user_roles WHERE user_id = ? ORDER BY updated_at DESC",
            { rs, _ ->
                UserIdentityRoleView(
                    roleCode = rs.getString("role_code"),
                    status = rs.getString("status"),
                    activatedAt = rs.getTimestamp("activated_at")?.toLocalDateTime(),
                    revokedAt = rs.getTimestamp("revoked_at")?.toLocalDateTime()
                )
            },
            userId
        )
        val applications = jdbcTemplate.query(
            """
            SELECT id, role_code, status, review_note, submitted_at, reviewed_at
            FROM identity_applications
            WHERE user_id = ?
            ORDER BY submitted_at DESC
            """.trimIndent(),
            { rs, _ ->
                UserIdentityApplicationView(
                    id = rs.getString("id"),
                    roleCode = rs.getString("role_code"),
                    status = rs.getString("status"),
                    reviewNote = rs.getString("review_note"),
                    submittedAt = rs.getTimestamp("submitted_at")?.toLocalDateTime(),
                    reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime()
                )
            },
            userId
        )
        return IdentityOverviewView(roles, applications)
    }

    @Transactional
    fun submit(userId: String, request: SubmitIdentityApplicationRequest): UserIdentityApplicationView {
        val roleCode = request.roleCode.trim().uppercase()
        require(roleCode in SELF_SERVICE_ROLES) { "暂不支持申请该身份" }
        accountLifecycleGuard.requireActiveForWrite(userId)
        require(count("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_code = ? AND status = 'ACTIVE'", userId, roleCode) == 0L) {
            "该身份已经认证通过"
        }
        require(count("SELECT COUNT(*) FROM identity_applications WHERE user_id = ? AND role_code = ? AND status = 'PENDING'", userId, roleCode) == 0L) {
            "该身份已有待审核申请，请勿重复提交"
        }

        validateApplicationData(roleCode, request.applicationData)
        validateDocuments(userId, roleCode, request.documents)
        val applicationJson = objectMapper.writeValueAsString(request.applicationData)
        require(applicationJson.length <= 20_000) { "认证信息内容过长" }

        val applicationId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO identity_applications
                (id, user_id, role_code, status, application_data, review_note, submitted_at)
            VALUES (?, ?, ?, 'PENDING', CAST(? AS JSON), '', NOW())
            """.trimIndent(),
            applicationId,
            userId,
            roleCode,
            applicationJson
        )
        request.documents.distinctBy { it.fileId }.forEach { document ->
            jdbcTemplate.update(
                "INSERT INTO identity_application_documents (application_id, file_id, document_type) VALUES (?, ?, ?)",
                applicationId,
                document.fileId,
                document.documentType.trim().uppercase()
            )
        }
        return UserIdentityApplicationView(
            id = applicationId,
            roleCode = roleCode,
            status = "PENDING",
            reviewNote = "",
            submittedAt = LocalDateTime.now(),
            reviewedAt = null
        )
    }

    private fun validateApplicationData(roleCode: String, data: Map<String, Any?>) {
        fun required(key: String, label: String, maxLength: Int = 200): String {
            val value = data[key]?.toString()?.trim().orEmpty()
            require(value.isNotEmpty()) { "$label 不能为空" }
            require(value.length <= maxLength) { "$label 内容过长" }
            return value
        }

        val realName = required("realName", "真实姓名", 50)
        require(realName.length in 2..50) { "请输入正确的真实姓名" }
        val idNumber = required("idNumber", "证件号码", 30)
        require(idNumber.matches(Regex("^[0-9A-Za-z]{6,30}$"))) { "证件号码格式不正确" }

        when (roleCode) {
            "INSTITUTION_LEGAL_REPRESENTATIVE" -> {
                required("phone", "联系电话", 30)
                required("institutionName", "机构名称", 200)
                required("businessLicenseNo", "统一社会信用代码", 32)
                required("region", "所在地区", 100)
                required("address", "详细地址", 300)
            }
            "DOCTOR" -> {
                require(!data.containsKey("hospitalName")) { "医生身份申请不允许填写执业机构" }
                required("department", "科室", 100)
                required("title", "职称", 100)
                required("qualificationNo", "医师资格证编号", 100)
                required("practiceNo", "医师执业证编号", 100)
                required("reason", "申请理由", 500)
            }
            "CONSULTANT" -> {
                required("phone", "联系电话", 30)
                required("experience", "从业经历", 1000)
                required("proofDescription", "证明材料说明", 500)
                required("reason", "申请理由", 500)
            }
        }
    }

    private fun validateDocuments(userId: String, roleCode: String, documents: List<IdentityApplicationDocumentRequest>) {
        require(documents.isNotEmpty()) { "请上传认证材料" }
        require(documents.size <= 10) { "认证材料数量不能超过 10 个" }
        require(documents.map { it.fileId }.distinct().size == documents.size) { "认证材料不能重复" }
        val normalizedTypes = documents.map { it.documentType.trim().uppercase() }.toSet()
        val requiredTypes = REQUIRED_DOCUMENTS.getValue(roleCode)
        require(normalizedTypes.containsAll(requiredTypes)) {
            "请完整上传${requiredTypes.joinToString("、")}"
        }
        documents.forEach { document ->
            val type = document.documentType.trim().uppercase()
            require(type in IDENTITY_DOCUMENT_TYPES) { "存在不支持的认证材料" }
            val ownedFile = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM private_files WHERE id = ? AND owner_user_id = ? AND purpose = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
                Long::class.java,
                document.fileId,
                userId,
                type
            )
            require(ownedFile == 1L) { "认证材料不存在、已失效或不属于当前用户" }
        }
    }

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)
}

data class SubmitIdentityApplicationRequest(
    val roleCode: String,
    val applicationData: Map<String, Any?> = emptyMap(),
    val documents: List<IdentityApplicationDocumentRequest> = emptyList()
)

data class IdentityApplicationDocumentRequest(
    val fileId: String,
    val documentType: String
)

data class IdentityOverviewView(
    val roles: List<UserIdentityRoleView>,
    val applications: List<UserIdentityApplicationView>
)

data class UserIdentityRoleView(
    val roleCode: String,
    val status: String,
    val activatedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?
)

data class UserIdentityApplicationView(
    val id: String,
    val roleCode: String,
    val status: String,
    val reviewNote: String,
    val submittedAt: LocalDateTime?,
    val reviewedAt: LocalDateTime?
)
