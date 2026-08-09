package com.joysong.server.order.service

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

private val SPLIT_PROPOSAL_SIDES = setOf("DOCTOR", "INSTITUTION")

@Service
class SplitConfigProposalService(
    private val jdbcTemplate: JdbcTemplate,
    private val managementAccessService: ManagementAccessService,
    private val splitRatePolicy: OrderSplitRatePolicy
) {
    fun list(actor: ManagementActor): List<SplitConfigProposalView> {
        val rows = jdbcTemplate.query(
            """
            SELECT sp.id, sp.config_id, sp.doctor_id, d.name AS doctor_name,
                   sp.institution_project_id, ip.institution_id, i.name AS institution_name,
                   COALESCE(ip.name, p.name) AS project_name, sp.consultation_fee,
                   sp.commission_rate, sp.institution_rate, sp.proposer_user_id,
                   proposer.nickname AS proposer_name, sp.proposer_side, sp.status,
                   sp.doctor_confirmed_at, sp.institution_confirmed_at,
                   sp.decided_by, decider.nickname AS decider_name, sp.decision_note,
                   sp.submitted_at, sp.decided_at, sp.updated_at
            FROM split_config_proposals sp
            JOIN doctors d ON d.id = sp.doctor_id
            JOIN institution_projects ip ON ip.id = sp.institution_project_id
            JOIN institutions i ON i.id = ip.institution_id
            JOIN projects p ON p.id = ip.project_id
            JOIN users proposer ON proposer.id = sp.proposer_user_id
            LEFT JOIN users decider ON decider.id = sp.decided_by
            ORDER BY CASE sp.status WHEN 'PENDING' THEN 0 ELSE 1 END, sp.submitted_at DESC
            """.trimIndent()
        ) { rs, _ ->
            SplitConfigProposalView(
                id = rs.getString("id"),
                configId = rs.getString("config_id"),
                doctorId = rs.getString("doctor_id"),
                doctorName = rs.getString("doctor_name"),
                institutionProjectId = rs.getString("institution_project_id"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                projectName = rs.getString("project_name"),
                consultationFee = rs.getBigDecimal("consultation_fee"),
                commissionRate = rs.getBigDecimal("commission_rate"),
                institutionRate = rs.getBigDecimal("institution_rate"),
                proposerUserId = rs.getString("proposer_user_id"),
                proposerName = rs.getString("proposer_name"),
                proposerSide = rs.getString("proposer_side"),
                status = rs.getString("status"),
                doctorConfirmedAt = rs.getTimestamp("doctor_confirmed_at")?.toLocalDateTime(),
                institutionConfirmedAt = rs.getTimestamp("institution_confirmed_at")?.toLocalDateTime(),
                decidedBy = rs.getString("decided_by"),
                deciderName = rs.getString("decider_name"),
                decisionNote = rs.getString("decision_note").orEmpty(),
                submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
                decidedAt = rs.getTimestamp("decided_at")?.toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
            )
        }
        if (actor.isAdmin) return rows
        return rows.filter { it.doctorId == actor.doctorId || it.institutionId in actor.managedInstitutionIds }
    }

    @Transactional
    fun submit(actor: ManagementActor, request: SplitConfigProposalRequest): SplitConfigProposalView {
        validateRates(request)
        val doctorId = request.doctorId.trim().also { require(it.isNotEmpty()) { "医生不能为空" } }
        val institutionProjectId = request.institutionProjectId.trim().also { require(it.isNotEmpty()) { "机构项目不能为空" } }
        managementAccessService.requireSplitConfig(actor, doctorId, institutionProjectId)
        val institutionId = institutionIdForProject(institutionProjectId)
            ?: throw IllegalArgumentException("机构项目不存在")
        val side = resolveSide(actor, doctorId, institutionId, request.proposerSide)
        require(pendingCount(doctorId, institutionProjectId) == 0L) { "该医生与机构项目已有待确认分账方案" }

        val id = UUID.randomUUID().toString()
        val configId = jdbcTemplate.query(
            "SELECT id FROM doctor_institution_project_configs WHERE doctor_id = ? AND institution_project_id = ? ORDER BY deleted_at IS NULL DESC LIMIT 1",
            { rs, _ -> rs.getString("id") },
            doctorId,
            institutionProjectId
        ).firstOrNull()
        jdbcTemplate.update(
            """
            INSERT INTO split_config_proposals
                (id, config_id, doctor_id, institution_project_id, consultation_fee,
                 commission_rate, institution_rate, proposer_user_id, proposer_side, status,
                 doctor_confirmed_at, institution_confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                    IF(? = 'DOCTOR', NOW(), NULL), IF(? = 'INSTITUTION', NOW(), NULL))
            """.trimIndent(),
            id,
            configId,
            doctorId,
            institutionProjectId,
            request.consultationFee,
            request.commissionRate,
            request.institutionRate,
            actor.userId,
            side,
            side,
            side
        )
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "分账提案创建失败" }
    }

    @Transactional
    fun confirm(actor: ManagementActor, id: String, confirmationSide: String?): SplitConfigProposalView {
        val target = lockedTarget(id) ?: throw IllegalArgumentException("分账提案不存在")
        require(target.status == "PENDING") { "分账提案已处理" }
        val side = if (actor.isAdmin) null else resolveSide(
            actor,
            target.doctorId,
            target.institutionId,
            confirmationSide
        )
        when (side) {
            "DOCTOR" -> {
                require(target.doctorConfirmedAt == null) { "医生已确认该方案" }
                jdbcTemplate.update("UPDATE split_config_proposals SET doctor_confirmed_at = NOW() WHERE id = ?", id)
            }
            "INSTITUTION" -> {
                require(target.institutionConfirmedAt == null) { "机构已确认该方案" }
                jdbcTemplate.update("UPDATE split_config_proposals SET institution_confirmed_at = NOW() WHERE id = ?", id)
            }
            null -> jdbcTemplate.update(
                "UPDATE split_config_proposals SET doctor_confirmed_at = COALESCE(doctor_confirmed_at, NOW()), institution_confirmed_at = COALESCE(institution_confirmed_at, NOW()) WHERE id = ?",
                id
            )
        }
        val confirmed = lockedTarget(id) ?: error("分账提案不存在")
        if (confirmed.doctorConfirmedAt != null && confirmed.institutionConfirmedAt != null) {
            val configId = applyProposal(confirmed)
            jdbcTemplate.update(
                "UPDATE split_config_proposals SET config_id = ?, status = 'APPROVED', decided_by = ?, decided_at = NOW() WHERE id = ? AND status = 'PENDING'",
                configId,
                actor.userId,
                id
            )
        }
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "分账确认结果读取失败" }
    }

    @Transactional
    fun reject(actor: ManagementActor, id: String, decisionNote: String): SplitConfigProposalView {
        require(decisionNote.isNotBlank()) { "拒绝分账方案时必须填写原因" }
        val target = lockedTarget(id) ?: throw IllegalArgumentException("分账提案不存在")
        require(target.status == "PENDING") { "分账提案已处理" }
        if (!actor.isAdmin) {
            val entitled = actor.doctorId == target.doctorId || target.institutionId in actor.managedInstitutionIds
            if (!entitled) throw AccessDeniedException("无权处理该分账提案")
            require(actor.userId != target.proposerUserId) { "提案发起方应撤回方案，不能自行拒绝" }
        }
        jdbcTemplate.update(
            "UPDATE split_config_proposals SET status = 'REJECTED', decided_by = ?, decision_note = ?, decided_at = NOW() WHERE id = ? AND status = 'PENDING'",
            actor.userId,
            decisionNote.trim().take(1000),
            id
        )
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "分账拒绝结果读取失败" }
    }

    @Transactional
    fun withdraw(actor: ManagementActor, id: String): SplitConfigProposalView {
        val target = lockedTarget(id) ?: throw IllegalArgumentException("分账提案不存在")
        require(target.status == "PENDING") { "只有待确认提案可以撤回" }
        if (!actor.isAdmin && actor.userId != target.proposerUserId) {
            throw AccessDeniedException("只能撤回自己发起的分账提案")
        }
        jdbcTemplate.update(
            "UPDATE split_config_proposals SET status = 'WITHDRAWN', decided_by = ?, decided_at = NOW() WHERE id = ? AND status = 'PENDING'",
            actor.userId,
            id
        )
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "分账撤回结果读取失败" }
    }

    private fun applyProposal(target: ProposalTarget): String {
        val configId = target.configId ?: UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institution_project_configs
                (id, doctor_id, institution_project_id, consultation_fee, commission_rate, institution_rate, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL)
            ON DUPLICATE KEY UPDATE
                consultation_fee = VALUES(consultation_fee), commission_rate = VALUES(commission_rate),
                institution_rate = VALUES(institution_rate), deleted_at = NULL, updated_at = NOW()
            """.trimIndent(),
            configId,
            target.doctorId,
            target.institutionProjectId,
            target.consultationFee,
            target.commissionRate,
            target.institutionRate
        )
        return jdbcTemplate.query(
            "SELECT id FROM doctor_institution_project_configs WHERE doctor_id = ? AND institution_project_id = ? AND deleted_at IS NULL",
            { rs, _ -> rs.getString("id") },
            target.doctorId,
            target.institutionProjectId
        ).first()
    }

    private fun validateRates(request: SplitConfigProposalRequest) {
        require(request.consultationFee >= BigDecimal.ZERO) { "面诊金不能为负数" }
        splitRatePolicy.resolve(
            institutionRate = request.institutionRate,
            consultantRate = request.commissionRate
        )
    }

    private fun resolveSide(actor: ManagementActor, doctorId: String, institutionId: String, requested: String?): String {
        val normalized = requested?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
        if (normalized != null) require(normalized in SPLIT_PROPOSAL_SIDES) { "确认方类型不正确" }
        val canDoctor = actor.doctorId == doctorId
        val canInstitution = institutionId in actor.managedInstitutionIds
        val side = normalized ?: when {
            canDoctor -> "DOCTOR"
            canInstitution -> "INSTITUTION"
            else -> throw AccessDeniedException("无权处理该分账方案")
        }
        if (side == "DOCTOR" && !canDoctor) throw AccessDeniedException("只有对应医生可以代表医生确认")
        if (side == "INSTITUTION" && !canInstitution) throw AccessDeniedException("只有机构法人可以代表机构确认")
        return side
    }

    private fun institutionIdForProject(institutionProjectId: String): String? = jdbcTemplate.query(
        "SELECT institution_id FROM institution_projects WHERE id = ? AND deleted_at IS NULL",
        { rs, _ -> rs.getString("institution_id") },
        institutionProjectId
    ).firstOrNull()

    private fun pendingCount(doctorId: String, institutionProjectId: String): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM split_config_proposals WHERE doctor_id = ? AND institution_project_id = ? AND status = 'PENDING'",
        Long::class.java,
        doctorId,
        institutionProjectId
    )

    private fun lockedTarget(id: String): ProposalTarget? = jdbcTemplate.query(
        """
        SELECT sp.config_id, sp.doctor_id, sp.institution_project_id, ip.institution_id,
               sp.consultation_fee, sp.commission_rate, sp.institution_rate,
               sp.proposer_user_id, sp.status, sp.doctor_confirmed_at, sp.institution_confirmed_at
        FROM split_config_proposals sp
        JOIN institution_projects ip ON ip.id = sp.institution_project_id
        WHERE sp.id = ? FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            ProposalTarget(
                configId = rs.getString("config_id"),
                doctorId = rs.getString("doctor_id"),
                institutionProjectId = rs.getString("institution_project_id"),
                institutionId = rs.getString("institution_id"),
                consultationFee = rs.getBigDecimal("consultation_fee"),
                commissionRate = rs.getBigDecimal("commission_rate"),
                institutionRate = rs.getBigDecimal("institution_rate"),
                proposerUserId = rs.getString("proposer_user_id"),
                status = rs.getString("status"),
                doctorConfirmedAt = rs.getTimestamp("doctor_confirmed_at")?.toLocalDateTime(),
                institutionConfirmedAt = rs.getTimestamp("institution_confirmed_at")?.toLocalDateTime()
            )
        },
        id
    ).firstOrNull()
}

data class SplitConfigProposalRequest(
    val doctorId: String = "",
    val institutionProjectId: String = "",
    val consultationFee: BigDecimal = BigDecimal.ZERO,
    val commissionRate: BigDecimal = BigDecimal.ZERO,
    val institutionRate: BigDecimal = BigDecimal("40.00"),
    val proposerSide: String? = null
)

data class SplitConfigProposalView(
    val id: String,
    val configId: String?,
    val doctorId: String,
    val doctorName: String,
    val institutionProjectId: String,
    val institutionId: String,
    val institutionName: String,
    val projectName: String,
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val proposerUserId: String,
    val proposerName: String,
    val proposerSide: String,
    val status: String,
    val doctorConfirmedAt: LocalDateTime?,
    val institutionConfirmedAt: LocalDateTime?,
    val decidedBy: String?,
    val deciderName: String?,
    val decisionNote: String,
    val submittedAt: LocalDateTime,
    val decidedAt: LocalDateTime?,
    val updatedAt: LocalDateTime
)

private data class ProposalTarget(
    val configId: String?,
    val doctorId: String,
    val institutionProjectId: String,
    val institutionId: String,
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val proposerUserId: String,
    val status: String,
    val doctorConfirmedAt: LocalDateTime?,
    val institutionConfirmedAt: LocalDateTime?
)
