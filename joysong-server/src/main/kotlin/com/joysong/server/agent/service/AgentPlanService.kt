package com.joysong.server.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentPlanItemResponse
import com.joysong.server.agent.dto.AgentPlanResponse
import com.joysong.server.agent.entity.AgentPlanEntity
import com.joysong.server.agent.entity.AgentPlanItemEntity
import com.joysong.server.agent.repository.AgentPlanItemRepository
import com.joysong.server.agent.repository.AgentPlanRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.joysong.server.user.service.AccountLifecycleGuard

private data class EffectivePlanOffering(
    val detail: ProjectEntity,
    val price: java.math.BigDecimal
)

@Service
class AgentPlanService(
    private val planRepository: AgentPlanRepository,
    private val itemRepository: AgentPlanItemRepository,
    private val projectRepository: ProjectRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    private val profileService: AgentProfileService,
    private val assessmentService: AgentAssessmentService,
    private val objectMapper: ObjectMapper,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {
    @Transactional
    fun create(userId: String, assessmentId: String): AgentPlanResponse {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val assessment = assessmentService.getEntity(assessmentId, userId)
        require(assessment.status == "READY_FOR_PLANNING") { AgentText.value("当前评估尚不能生成规划：", "This assessment is not ready for planning: ") + assessment.status }
        val profile = profileService.requireEntity(userId)
        val goals = profileService.readList(assessment.goalSnapshotJson)
        val excluded = profileService.readList(profile.excludedProjectsJson)
            .map(String::trim)
            .filter(String::isNotBlank)

        val allProjects = projectRepository.findAll()
        val projectMap = allProjects.associateBy { it.id }
        val activeInstitutionProjects = institutionProjectRepository.findAll().filter { it.isActive }
        val minimumPrices = activeInstitutionProjects.takeIf { it.isNotEmpty() }
            ?.let { doctorProjectRepository.findPublicByInstitutionProjectIds(it.map { offering -> offering.id }) }
            .orEmpty()
            .groupBy { it.institutionProjectId }
            .mapValues { (_, bindings) -> bindings.minOf { it.price } }
        val effectiveOfferingsByProject = activeInstitutionProjects.asSequence()
            .filter { it.id in minimumPrices }
            .mapNotNull { offering ->
                val project = projectMap[offering.projectId] ?: return@mapNotNull null
                offering.projectId to EffectivePlanOffering(
                    detail = institutionProjectDetailResolver.resolve(offering, project),
                    price = minimumPrices.getValue(offering.id)
                )
            }
            .groupBy({ it.first }, { it.second })
        val candidates = if (goals.isEmpty()) emptyList() else allProjects
            .asSequence()
            .filter { effectiveOfferingsByProject[it.id].orEmpty().isNotEmpty() }
            .filterNot { project ->
                isExcluded(project, effectiveOfferingsByProject[project.id].orEmpty(), excluded)
            }
            .map { project -> project to matchScore(project, effectiveOfferingsByProject[project.id].orEmpty(), goals, profile.budgetMax) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ProjectEntity, Int>> { it.second }.thenByDescending { it.first.rating })
            .take(5)
            .toList()
        val version = planRepository.countByUserId(userId).toInt() + 1
        val planStatus = if (candidates.isEmpty()) "NEEDS_HUMAN_REVIEW" else "READY"
        val downtimePreference = profile.acceptableDowntimeDays
            ?.let { AgentText.value("${it}天", "$it days") }
            ?: AgentText.value("未提供", "not provided")
        val painPreference = profile.painTolerance.ifBlank { AgentText.value("未提供", "not provided") }
        val preferenceSummary = AgentText.value(
            "可接受恢复期偏好：$downtimePreference；疼痛接受度偏好：$painPreference。",
            "Acceptable downtime preference: $downtimePreference; pain tolerance preference: $painPreference."
        )
        val summary = if (candidates.isEmpty()) {
            AgentText.value(
                "以下内容仅作为信息参考，不构成诊断或治疗建议。平台现有信息不足以整理出与目标相关的候选项目；$preferenceSummary 当前目录缺少结构化恢复期、疼痛、禁忌与风险数据，需向机构或具备资质的医生确认。",
                "This is for information reference only and is not diagnosis or treatment advice. The platform currently has insufficient information to identify goal-related candidates. $preferenceSummary The catalog lacks structured downtime, pain, contraindication, and risk data; confirm these with the institution or a qualified clinician."
            )
        } else {
            AgentText.value(
                "以下候选项目仅作为信息参考，不构成诊断或治疗建议。候选项目按已确认的改善目标、预算和平台目录字段整理；$preferenceSummary 恢复期与疼痛偏好未参与候选排序。当前目录缺少结构化恢复期、疼痛、禁忌与风险数据，需向机构或具备资质的医生确认。",
                "These candidates are for information reference only and are not diagnosis or treatment advice. They are organized from confirmed goals, budget, and platform catalog fields. $preferenceSummary Downtime and pain preferences did not affect candidate ranking. The catalog lacks structured downtime, pain, contraindication, and risk data; confirm these with the institution or a qualified clinician."
            )
        }
        val plan = planRepository.save(
            AgentPlanEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                assessmentId = assessmentId,
                version = version,
                status = planStatus,
                summary = summary,
                totalBudgetMin = profile.budgetMin,
                totalBudgetMax = profile.budgetMax
            )
        )

        val items = candidates.mapIndexed { index, (project, _) ->
            itemRepository.save(buildItem(plan.id, project, effectiveOfferingsByProject[project.id].orEmpty(), goals, index))
        }
        return plan.toResponse(items)
    }

    fun get(userId: String, planId: String): AgentPlanResponse {
        val plan = planRepository.findByIdAndUserId(planId, userId) ?: throw IllegalArgumentException(AgentText.value("规划不存在", "Plan not found"))
        return plan.toResponse(itemRepository.findByPlanIdOrderBySortOrderAsc(planId))
    }

    fun list(userId: String): List<AgentPlanResponse> = planRepository.findByUserIdOrderByVersionDesc(userId).map {
        it.toResponse(itemRepository.findByPlanIdOrderBySortOrderAsc(it.id))
    }

    @Transactional
    fun delete(userId: String, planId: String) {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val plan = planRepository.findByIdAndUserId(planId, userId)
            ?: throw IllegalArgumentException(AgentText.value("规划不存在", "Plan not found"))
        itemRepository.deleteAll(itemRepository.findByPlanIdOrderBySortOrderAsc(planId))
        planRepository.delete(plan)
    }

    @Transactional
    fun clear(userId: String) {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        planRepository.findByUserIdOrderByVersionDesc(userId).forEach { plan ->
            itemRepository.deleteAll(itemRepository.findByPlanIdOrderBySortOrderAsc(plan.id))
            planRepository.delete(plan)
        }
    }

    private fun matchScore(
        project: ProjectEntity,
        offerings: List<EffectivePlanOffering>,
        goals: List<String>,
        budgetMax: java.math.BigDecimal?
    ): Int {
        val searchable = structuredSearchText(project, offerings)
        var score = goals.sumOf { goal -> if (searchable.contains(goal.lowercase())) 4 else relatedTerms(goal).count { searchable.contains(it) } }
        if (score == 0) return 0
        if (budgetMax != null && offerings.any { it.price > java.math.BigDecimal.ZERO && it.price <= budgetMax }) score += 2
        if ((sequenceOf(project.rating) + offerings.asSequence().map { it.detail.rating })
                .any { it >= java.math.BigDecimal("4.5") }
        ) score += 1
        return score
    }

    private fun relatedTerms(goal: String): List<String> = when {
        goal.contains("抗衰") || goal.contains("松弛") || goal.contains("皱纹") || goal.contains("anti-aging", true) || goal.contains("anti aging", true) || goal.contains("sagging", true) || goal.contains("wrinkle", true) -> listOf("抗衰", "紧致", "提升", "除皱", "皱纹", "anti-aging", "lifting", "wrinkle")
        goal.contains("痘") || goal.contains("acne", true) || goal.contains("scar", true) -> listOf("痘", "痤疮", "痘印", "痘坑", "acne", "scar")
        goal.contains("斑") || goal.contains("色素") || goal.contains("pigment", true) || goal.contains("spot", true) || goal.contains("brighten", true) -> listOf("祛斑", "色素", "美白", "pigment", "spot", "brightening")
        goal.contains("轮廓") || goal.contains("瘦脸") || goal.contains("contour", true) || goal.contains("slimming", true) || goal.contains("jaw", true) -> listOf("轮廓", "瘦脸", "塑形", "contour", "slimming", "jawline")
        goal.contains("补水") || goal.contains("肤质") || goal.contains("hydration", true) || goal.contains("texture", true) || goal.contains("pore", true) -> listOf("补水", "肤质", "嫩肤", "毛孔", "hydration", "texture", "pore")
        else -> listOf(goal.lowercase())
    }

    private fun buildItem(
        planId: String,
        project: ProjectEntity,
        offerings: List<EffectivePlanOffering>,
        goals: List<String>,
        index: Int
    ): AgentPlanItemEntity {
        val searchable = structuredSearchText(project, offerings)
        val matchedGoals = goals.filter { goal ->
            searchable.contains(goal.lowercase()) || relatedTerms(goal).any(searchable::contains)
        }
        return AgentPlanItemEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            stageName = if (index == 0) AgentText.value("信息参考", "Information reference") else AgentText.value("补充参考", "Additional reference"),
            projectId = project.id,
            projectName = project.name,
            recommendationType = "REFERENCE",
            reason = AgentText.value("平台项目名称、分类或标签与", "Platform project names, categories, or tags are related to ") + matchedGoals.ifEmpty { goals }.joinToString(AgentText.value("、", ", ")) + AgentText.value("；价格在预算范围内时仅用于候选排序。", "; price is used only for candidate ordering when it is within budget."),
            expectedBenefit = AgentText.value("平台目录只显示该项目与已确认目标存在关键词关联，实际效果需面诊确认。", "The platform catalog only shows a keyword relationship with the confirmed goal; actual outcomes require an in-person consultation."),
            limitations = AgentText.value("平台资料不能确定个人适用性、治疗参数、恢复期、疼痛程度、禁忌或最终效果。", "Platform information cannot determine personal suitability, treatment parameters, downtime, pain, contraindications, or final outcomes."),
            risksJson = objectMapper.writeValueAsString(listOf(AgentText.value("风险信息：需向机构确认", "Risk information: confirm with the institution"))),
            alternativesJson = "[]",
            requiredConfirmationJson = objectMapper.writeValueAsString(listOf(
                AgentText.value("恢复期：需向机构确认", "Downtime: confirm with the institution"),
                AgentText.value("疼痛程度：需向机构确认", "Pain level: confirm with the institution"),
                AgentText.value("禁忌与风险：需向机构确认", "Contraindications and risks: confirm with the institution"),
                AgentText.value("医生与机构资质", "Clinician and institution credentials"),
                AgentText.value("产品或设备规格", "Product or device specifications"),
                AgentText.value("完整费用", "Total cost")
            )),
            confidence = "LOW",
            sortOrder = index
        )
    }

    private fun structuredSearchText(
        project: ProjectEntity,
        offerings: List<EffectivePlanOffering>
    ): String = buildString {
        append("${project.name} ${project.category} ${project.tags} ${project.categoryTags}")
        offerings.forEach { offering ->
            val detail = offering.detail
            append(" ${detail.name} ${detail.category} ${detail.tags} ${detail.categoryTags}")
        }
    }.lowercase()

    private fun isExcluded(
        project: ProjectEntity,
        offerings: List<EffectivePlanOffering>,
        excludedProjects: List<String>
    ): Boolean = excludedProjects.any { excluded ->
        project.id.equals(excluded, ignoreCase = true) ||
            project.name.equals(excluded, ignoreCase = true) ||
            offerings.any { it.detail.name.equals(excluded, ignoreCase = true) }
    }

    private fun AgentPlanEntity.toResponse(items: List<AgentPlanItemEntity>) = AgentPlanResponse(
        id = id,
        assessmentId = assessmentId,
        version = version,
        status = status,
        summary = safeSummary(summary),
        totalBudgetMin = totalBudgetMin,
        totalBudgetMax = totalBudgetMax,
        items = items.mapIndexed { index, item ->
            AgentPlanItemResponse(
                id = item.id,
                stage = if (index == 0) AgentText.value("信息参考", "Information reference") else AgentText.value("补充参考", "Additional reference"),
                projectId = item.projectId,
                projectName = item.projectName,
                recommendationType = "REFERENCE",
                reason = AgentText.value("平台仅保留该项目的名称与基础目录信息供进一步核对；不代表目标匹配、个人适用性或治疗建议。", "The platform only retains the project name and basic catalog information for further review; this does not indicate goal matching, personal suitability, or treatment advice."),
                expectedBenefit = AgentText.value("平台目录不能确定实际效果，需由具备资质的医生面诊确认。", "The platform catalog cannot determine actual outcomes; confirm them during an in-person consultation with a qualified clinician."),
                limitations = AgentText.value("平台资料不能确定个人适用性、治疗参数、恢复期、疼痛程度、禁忌或最终效果。", "Platform information cannot determine personal suitability, treatment parameters, downtime, pain, contraindications, or final outcomes."),
                risks = listOf(AgentText.value("风险信息：需向机构确认", "Risk information: confirm with the institution")),
                alternatives = emptyList(),
                requiredConfirmations = listOf(
                    AgentText.value("恢复期：需向机构确认", "Downtime: confirm with the institution"),
                    AgentText.value("疼痛程度：需向机构确认", "Pain level: confirm with the institution"),
                    AgentText.value("禁忌与风险：需向机构确认", "Contraindications and risks: confirm with the institution")
                ),
                confidence = "LOW"
            )
        },
        createdAt = createdAt.toString()
    )

    private fun safeSummary(storedSummary: String): String {
        val hasBoundary = if (AgentText.isChinese()) {
            storedSummary.contains("信息参考") && storedSummary.contains("不构成诊断或治疗建议")
        } else {
            storedSummary.contains("information reference", ignoreCase = true) &&
                storedSummary.contains("not diagnosis or treatment advice", ignoreCase = true)
        }
        val prohibited = listOf(
            "最适合", "为你制定", "为您制定", "治疗方案", "已结合恢复期", "已结合疼痛",
            "best for you", "tailored for you", "personalized treatment plan"
        ).any { storedSummary.contains(it, ignoreCase = true) }
        if (hasBoundary && !prohibited) return storedSummary
        return AgentText.value(
            "以下项目仅作为平台信息参考，不构成诊断或治疗建议。个人适用性、恢复期、疼痛程度、禁忌与风险需向机构或具备资质的医生确认。",
            "These items are platform information reference only and are not diagnosis or treatment advice. Personal suitability, downtime, pain, contraindications, and risks require confirmation with the institution or a qualified clinician."
        )
    }
}
