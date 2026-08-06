package com.joysong.server.agent.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentPlanItemResponse
import com.joysong.server.agent.dto.AgentPlanResponse
import com.joysong.server.agent.entity.AgentPlanEntity
import com.joysong.server.agent.entity.AgentPlanItemEntity
import com.joysong.server.agent.entity.AgentToolAuditEntity
import com.joysong.server.agent.repository.AgentPlanItemRepository
import com.joysong.server.agent.repository.AgentPlanRepository
import com.joysong.server.agent.repository.AgentToolAuditRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

private data class EffectivePlanOffering(
    val detail: ProjectEntity,
    val price: java.math.BigDecimal
)

@Service
class AgentPlanService(
    private val planRepository: AgentPlanRepository,
    private val itemRepository: AgentPlanItemRepository,
    private val auditRepository: AgentToolAuditRepository,
    private val projectRepository: ProjectRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    private val profileService: AgentProfileService,
    private val assessmentService: AgentAssessmentService,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun create(userId: String, assessmentId: String): AgentPlanResponse {
        val assessment = assessmentService.getEntity(assessmentId, userId)
        require(assessment.status == "READY_FOR_PLANNING") { AgentText.value("当前评估尚不能生成规划：", "This assessment is not ready for planning: ") + assessment.status }
        val profile = profileService.requireEntity(userId)
        val goals = profileService.readList(assessment.goalSnapshotJson)
        val excluded = profileService.readList(profile.excludedProjectsJson)

        val startedAt = Instant.now()
        val allProjects = projectRepository.findAll()
        val projectMap = allProjects.associateBy { it.id }
        val effectiveOfferingsByProject = institutionProjectRepository.findAll().asSequence()
            .filter { it.isActive }
            .mapNotNull { offering ->
                val project = projectMap[offering.projectId] ?: return@mapNotNull null
                offering.projectId to EffectivePlanOffering(
                    detail = institutionProjectDetailResolver.resolve(offering, project),
                    price = offering.price
                )
            }
            .groupBy({ it.first }, { it.second })
        val candidates = if (goals.isEmpty()) emptyList() else allProjects
            .asSequence()
            .filterNot { project -> excluded.any { excludedName -> project.name.contains(excludedName, true) } }
            .map { project -> project to matchScore(project, effectiveOfferingsByProject[project.id].orEmpty(), goals, profile.budgetMax) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ProjectEntity, Int>> { it.second }.thenByDescending { it.first.rating })
            .take(5)
            .toList()
        auditRepository.save(
            AgentToolAuditEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                toolName = "SEARCH_PLATFORM_PROJECTS",
                requestSummary = objectMapper.writeValueAsString(mapOf("goals" to goals, "destinationCity" to profile.city)),
                detectedKeywords = (goals + profile.city).filter { it.isNotBlank() }.distinct().joinToString(",").take(1000),
                resultStatus = "SUCCESS:${candidates.size}",
                durationMs = Duration.between(startedAt, Instant.now()).toMillis()
            )
        )

        val version = planRepository.countByUserId(userId).toInt() + 1
        val planStatus = if (candidates.isEmpty()) "NEEDS_HUMAN_REVIEW" else "READY"
        val summary = if (candidates.isEmpty()) {
            AgentText.value("平台现有信息不足以匹配你的目标，本次不生成猜测性推荐，建议补充目标或转人工咨询。", "The available platform data does not match your goals. No speculative recommendation was created; add more detail or request human support.")
        } else {
            AgentText.value("根据你确认的目标、预算和恢复期，生成了分阶段候选方案。具体适用性、剂量和操作方式仍需由具备资质的医生面诊确认。", "A phased shortlist was created from your confirmed goals, budget, and acceptable downtime. Suitability, dosage, and treatment parameters still require an in-person consultation with a qualified clinician.")
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

        val items = candidates.mapIndexed { index, (project, score) ->
            itemRepository.save(buildItem(plan.id, project, effectiveOfferingsByProject[project.id].orEmpty(), goals, score, index))
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
        val plan = planRepository.findByIdAndUserId(planId, userId)
            ?: throw IllegalArgumentException(AgentText.value("规划不存在", "Plan not found"))
        itemRepository.deleteAll(itemRepository.findByPlanIdOrderBySortOrderAsc(planId))
        planRepository.delete(plan)
    }

    @Transactional
    fun clear(userId: String) {
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
        val searchable = buildString {
            append("${project.name} ${project.category} ${project.tags} ${project.categoryTags} ${project.description} ${project.slogan}")
            offerings.forEach { offering ->
                val detail = offering.detail
                append(" ${detail.name} ${detail.category} ${detail.tags} ${detail.description} ${detail.slogan} ${plainText(detail.detailContent)}")
            }
        }.lowercase()
        var score = goals.sumOf { goal -> if (searchable.contains(goal.lowercase())) 4 else relatedTerms(goal).count { searchable.contains(it) } }
        if (budgetMax != null && (
                (project.referencePrice > java.math.BigDecimal.ZERO && project.referencePrice <= budgetMax) ||
                    offerings.any { it.price > java.math.BigDecimal.ZERO && it.price <= budgetMax }
                )) score += 2
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
        score: Int,
        index: Int
    ): AgentPlanItemEntity {
        val matchedGoals = goals.filter { goal ->
            val searchable = buildString {
                append("${project.name}${project.category}${project.tags}${project.description}${project.slogan}")
                offerings.forEach {
                    append("${it.detail.name}${it.detail.category}${it.detail.tags}${it.detail.description}${it.detail.slogan}${plainText(it.detail.detailContent)}")
                }
            }.lowercase()
            searchable.contains(goal.lowercase()) || relatedTerms(goal).any(searchable::contains)
        }
        val effectiveDescription = offerings.asSequence().map { it.detail.description }.firstOrNull { description ->
            goals.any { goal -> description.contains(goal, true) || relatedTerms(goal).any { description.contains(it, true) } }
        }.orEmpty().ifBlank { project.description }
        return AgentPlanItemEntity(
            id = UUID.randomUUID().toString(),
            planId = planId,
            stageName = if (index == 0) AgentText.value("优先了解", "Review first") else AgentText.value("备选比较", "Compare as an alternative"),
            projectId = project.id,
            projectName = project.name,
            recommendationType = if (index == 0) "CONSIDER" else "ALTERNATIVE",
            reason = AgentText.value("平台项目资料与", "Platform treatment information is related to ") + matchedGoals.ifEmpty { goals }.joinToString(AgentText.value("、", ", ")) + AgentText.value("相关，并结合了你的预算条件。", ", with your budget considered."),
            expectedBenefit = if (AgentText.isChinese()) effectiveDescription.ifBlank { "可能与已确认的改善目标相关，具体改善程度需面诊判断。" } else "It may relate to your confirmed goal; the degree of improvement requires an in-person consultation.",
            limitations = AgentText.value("平台资料只能用于项目初筛，不能确定个人适用性、治疗参数或最终效果。", "Platform information supports initial screening only and cannot determine personal suitability, treatment parameters, or final outcomes."),
            risksJson = objectMapper.writeValueAsString(listOf(AgentText.value("存在个体差异", "Individual results vary"), AgentText.value("可能有恢复期或不良反应", "Downtime or adverse effects may occur"), AgentText.value("需要医生排查禁忌", "A clinician must screen for contraindications"))),
            alternativesJson = "[]",
            requiredConfirmationJson = objectMapper.writeValueAsString(listOf(AgentText.value("医生与机构资质", "Clinician and institution credentials"), AgentText.value("产品或设备规格", "Product or device specifications"), AgentText.value("完整费用", "Total cost"), AgentText.value("风险与术后处理", "Risks and aftercare"))),
            confidence = if (score >= 7) "MEDIUM" else "LOW",
            sortOrder = index
        )
    }

    private fun plainText(content: String?): String = content.orEmpty()
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)

    private fun AgentPlanEntity.toResponse(items: List<AgentPlanItemEntity>) = AgentPlanResponse(
        id = id,
        assessmentId = assessmentId,
        version = version,
        status = status,
        summary = summary,
        totalBudgetMin = totalBudgetMin,
        totalBudgetMax = totalBudgetMax,
        items = items.map { item ->
            AgentPlanItemResponse(
                id = item.id,
                stage = item.stageName,
                projectId = item.projectId,
                projectName = item.projectName,
                recommendationType = item.recommendationType,
                reason = item.reason,
                expectedBenefit = item.expectedBenefit,
                limitations = item.limitations,
                risks = readList(item.risksJson),
                alternatives = readList(item.alternativesJson),
                requiredConfirmations = readList(item.requiredConfirmationJson),
                confidence = item.confidence
            )
        },
        createdAt = createdAt.toString()
    )

    private fun readList(json: String): List<String> = runCatching {
        objectMapper.readValue(json, object : TypeReference<List<String>>() {})
    }.getOrDefault(emptyList())
}
