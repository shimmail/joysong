package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service

private const val DOCTOR_ROLE = "DOCTOR"
private const val LEGAL_REP_ROLE = "INSTITUTION_LEGAL_REPRESENTATIVE"

@Service
class ManagementAccessService(
    private val jdbcTemplate: JdbcTemplate
) {
    fun actor(authentication: Authentication): ManagementActor {
        val userId = authentication.principal as? String
            ?: throw AccessDeniedException("无法识别当前用户")
        val admin = authentication.authorities.any { it.authority == "ROLE_ADMIN" }
        return actorFor(userId, admin)
    }

    fun contextFor(userId: String, platformRole: String): ManagementContextView =
        actorFor(userId, platformRole == "ADMIN").toContext()

    fun requireDoctor(actor: ManagementActor, doctorId: String) {
        if (!actor.isAdmin && doctorId !in actor.manageableDoctorIds) {
            throw AccessDeniedException("无权管理该医生信息")
        }
    }

    fun requireInstitutionVisible(actor: ManagementActor, institutionId: String) {
        if (!actor.isAdmin && institutionId !in actor.visibleInstitutionIds) {
            throw AccessDeniedException("无权查看该机构信息")
        }
    }

    fun requireInstitutionManaged(actor: ManagementActor, institutionId: String) {
        if (!actor.isAdmin && institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("只有该机构已确认的法人可以修改机构信息")
        }
    }

    fun requireArticleDoctor(actor: ManagementActor, doctorId: String) {
        requireDoctor(actor, doctorId)
    }

    fun canManageArticleDoctor(actor: ManagementActor, doctorId: String): Boolean =
        actor.isAdmin || doctorId in actor.manageableDoctorIds

    fun canViewInstitutionProject(actor: ManagementActor, institutionProjectId: String): Boolean {
        if (actor.isAdmin) return true
        val institutionId = institutionIdForProject(institutionProjectId) ?: return false
        if (institutionId in actor.managedInstitutionIds) return true
        return actor.doctorId != null && institutionId in actor.doctorInstitutionIds
    }

    fun requireSplitConfig(actor: ManagementActor, doctorId: String, institutionProjectId: String) {
        val institutionId = institutionIdForProject(institutionProjectId)
            ?: throw IllegalArgumentException("机构项目不存在")
        val doctorBound = count(
            """
            SELECT COUNT(*)
            FROM doctor_projects dp
            JOIN doctor_institutions di
              ON di.doctor_id = dp.doctor_id AND di.institution_id = ?
            WHERE dp.doctor_id = ? AND dp.institution_project_id = ?
              AND di.status = 'APPROVED' AND di.deleted_at IS NULL
            """.trimIndent(),
            institutionId,
            doctorId,
            institutionProjectId
        ) > 0
        if (actor.isAdmin) {
            require(doctorBound) { "医生尚未加入该机构项目，不能配置分账" }
            return
        }
        val allowed = when {
            actor.doctorId == doctorId -> doctorBound
            institutionId in actor.managedInstitutionIds -> doctorBound
            else -> false
        }
        if (!allowed) throw AccessDeniedException("无权配置该医生与机构项目的分账信息")
    }

    fun canManageSplitConfig(actor: ManagementActor, doctorId: String, institutionProjectId: String): Boolean =
        try {
            requireSplitConfig(actor, doctorId, institutionProjectId)
            true
        } catch (_: AccessDeniedException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun actorFor(userId: String, isAdmin: Boolean): ManagementActor {
        require(count("SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NULL", userId) == 1L) {
            "用户不存在或已注销"
        }
        if (isAdmin) {
            return ManagementActor(
                userId = userId,
                isAdmin = true,
                activeRoles = setOf("ADMIN"),
                doctorId = null,
                managedInstitutionIds = emptySet(),
                doctorInstitutionIds = emptySet(),
                manageableDoctorIds = emptySet()
            )
        }

        val activeRoles = jdbcTemplate.queryForList(
            "SELECT role_code FROM user_roles WHERE user_id = ? AND status = 'ACTIVE'",
            String::class.java,
            userId
        ).toSet()
        if (DOCTOR_ROLE !in activeRoles && LEGAL_REP_ROLE !in activeRoles) {
            throw AccessDeniedException("账号尚未取得医生或机构法人管理权限")
        }

        val doctorId = userId.takeIf { DOCTOR_ROLE in activeRoles && count(
            "SELECT COUNT(*) FROM doctors WHERE id = ? AND deleted_at IS NULL",
            userId
        ) == 1L }
        val managedInstitutionIds = if (LEGAL_REP_ROLE in activeRoles) {
            jdbcTemplate.queryForList(
                """
                SELECT institution_id
                FROM institution_memberships
                WHERE user_id = ? AND status = 'APPROVED'
                  AND member_role IN ('INSTITUTION_LEGAL_REPRESENTATIVE', 'LEGAL_REPRESENTATIVE')
                """.trimIndent(),
                String::class.java,
                userId
            ).toSet()
        } else emptySet()
        val doctorInstitutionIds = if (doctorId != null) {
            jdbcTemplate.queryForList(
                """
                SELECT institution_id
                FROM doctor_institutions
                WHERE doctor_id = ? AND status = 'APPROVED' AND deleted_at IS NULL
                """.trimIndent(),
                String::class.java,
                doctorId
            ).toSet()
        } else emptySet()
        val institutionDoctorIds = managedInstitutionIds.flatMap { institutionId ->
            jdbcTemplate.queryForList(
                """
                SELECT doctor_id
                FROM doctor_institutions
                WHERE institution_id = ? AND status = 'APPROVED' AND deleted_at IS NULL
                """.trimIndent(),
                String::class.java,
                institutionId
            )
        }.toSet()
        val manageableDoctorIds = buildSet {
            doctorId?.let(::add)
            addAll(institutionDoctorIds)
        }
        if (doctorId == null && managedInstitutionIds.isEmpty()) {
            throw AccessDeniedException("职业身份已通过，但管理档案或机构归属尚未建立，请联系平台处理")
        }

        return ManagementActor(
            userId = userId,
            isAdmin = false,
            activeRoles = activeRoles,
            doctorId = doctorId,
            managedInstitutionIds = managedInstitutionIds,
            doctorInstitutionIds = doctorInstitutionIds,
            manageableDoctorIds = manageableDoctorIds
        )
    }

    private fun institutionIdForProject(institutionProjectId: String): String? = jdbcTemplate.query(
        "SELECT institution_id FROM institution_projects WHERE id = ? AND deleted_at IS NULL",
        { rs, _ -> rs.getString("institution_id") },
        institutionProjectId
    ).firstOrNull()

    private fun ManagementActor.toContext() = ManagementContextView(
        userId = userId,
        platformRole = if (isAdmin) "ADMIN" else "USER",
        activeRoles = activeRoles.sorted(),
        doctorId = doctorId,
        managedInstitutionIds = managedInstitutionIds.sorted(),
        visibleInstitutionIds = visibleInstitutionIds.sorted(),
        doctorInstitutionIds = doctorInstitutionIds.sorted(),
        canManageDoctors = isAdmin || manageableDoctorIds.isNotEmpty(),
        canManageInstitutions = isAdmin || managedInstitutionIds.isNotEmpty(),
        canManageInstitutionProjects = isAdmin || doctorId != null || managedInstitutionIds.isNotEmpty(),
        canManageArticles = isAdmin || manageableDoctorIds.isNotEmpty(),
        canManageSplitConfigs = isAdmin || doctorId != null || managedInstitutionIds.isNotEmpty(),
        canManageOrders = isAdmin || doctorId != null || managedInstitutionIds.isNotEmpty()
    )

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)
}

data class ManagementActor(
    val userId: String,
    val isAdmin: Boolean,
    val activeRoles: Set<String>,
    val doctorId: String?,
    val managedInstitutionIds: Set<String>,
    val doctorInstitutionIds: Set<String>,
    val manageableDoctorIds: Set<String>
) {
    val visibleInstitutionIds: Set<String>
        get() = managedInstitutionIds + doctorInstitutionIds
}

data class ManagementContextView(
    val userId: String,
    val platformRole: String,
    val activeRoles: List<String>,
    val doctorId: String?,
    val managedInstitutionIds: List<String>,
    val visibleInstitutionIds: List<String>,
    val doctorInstitutionIds: List<String>,
    val canManageDoctors: Boolean,
    val canManageInstitutions: Boolean,
    val canManageInstitutionProjects: Boolean,
    val canManageArticles: Boolean,
    val canManageSplitConfigs: Boolean,
    val canManageOrders: Boolean
)
