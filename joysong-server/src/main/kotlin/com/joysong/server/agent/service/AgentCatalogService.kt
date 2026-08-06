package com.joysong.server.agent.service

import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportRequest
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.discover.service.DiscoverSearchRequest
import com.joysong.server.discover.service.RequestedEntityType
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service

private data class CatalogContextKeywords(
    val cities: Set<String> = emptySet(),
    val projects: Set<String> = emptySet(),
    val institutions: Set<String> = emptySet(),
    val doctors: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val offerings: Set<String> = emptySet(),
    val searchTerms: Set<String> = emptySet()
) {
    val all: Set<String>
        get() = cities + projects + institutions + doctors + categories + tags + offerings + searchTerms
}

private data class EffectiveCatalogOffering(
    val baseProject: ProjectEntity,
    val institution: InstitutionEntity?,
    val detail: ProjectEntity
)

@Service
class AgentCatalogService(
    private val institutionRepository: InstitutionRepository,
    private val doctorRepository: DoctorRepository,
    private val projectRepository: ProjectRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val discoverSearchService: DiscoverSearchService,
    private val doctorInstitutionService: DoctorInstitutionService,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver
) {
    /**
     * Detects a concrete institution-project reference before intent routing.
     * Nullable override columns are always resolved against their base project first.
     */
    fun hasInstitutionProjectMatch(query: String): Boolean {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.length < 2) return false
        val projects = projectRepository.findAll().associateBy { it.id }
        val terms = discoverSearchService.extractMatchingFragments(query)
        return institutionProjectRepository.findAll().asSequence()
            .filter { it.isActive }
            .any { offering ->
                val project = projects[offering.projectId] ?: return@any false
                offeringMatchesQuery(
                    query = normalizedQuery,
                    terms = terms,
                    offering = offering,
                    project = project
                )
            }
    }

    fun report(request: AgentCatalogReportRequest): AgentCatalogReportResponse {
        val query = request.query.trim()
        require(query.isNotEmpty()) { "查询内容不能为空" }
        require(query.length <= 1000) { "查询内容不能超过 1000 字" }
        require(request.mode.trim().uppercase() in setOf("AUTO", "SUMMARY", "COMPARISON")) {
            "报告模式仅支持 AUTO、SUMMARY 或 COMPARISON"
        }
        return report(
            request = request,
            mentionedCities = discoverSearchService.citiesMentionedIn(query),
            explicitlyRequestedTypes = discoverSearchService.explicitlyRequestedEntityTypes(query)
        )
    }

    private fun report(
        request: AgentCatalogReportRequest,
        mentionedCities: List<String>,
        explicitlyRequestedTypes: Set<RequestedEntityType>,
        searchQuery: String = request.query,
        targetOverride: AgentQueryTarget? = null
    ): AgentCatalogReportResponse {
        val query = request.query.trim()
        val effectiveSearchQuery = searchQuery.trim()
        val mode = if (request.mode.equals("AUTO", true)) {
            if (listOf("对比", "比较", "区别", "compare", "versus", " vs ").any { query.contains(it, true) }) "COMPARISON" else "SUMMARY"
        } else request.mode.uppercase()
        val reportTarget = targetOverride?.toReportTarget() ?: detectReportTarget(query)
        // Reuse Discover's city/project/tag matching so an unnamed comparison can
        // resolve concrete clinic offerings instead of asking the user for names.
        val unifiedSearch = discoverSearchService.search(
            DiscoverSearchRequest(
                query = effectiveSearchQuery,
                cities = mentionedCities,
                fallbackWhenNoMatch = explicitlyRequestedTypes.isEmpty(),
                limit = 12
            )
        )
        val discoveredProjects = unifiedSearch.projects
            .filter { it.institutionProjects.isNotEmpty() }
        val requestedCount = requestedInstitutionCount(query)
        val discoveredOfferings = discoveredProjects
            .flatMap { project -> project.institutionProjects.map { project to it } }
            .sortedWith(
                compareByDescending<Pair<com.joysong.server.discover.dto.ProjectWithInstitutionsResponse, com.joysong.server.discover.dto.InstitutionProjectItemResponse>> {
                    it.second.rating
                }.thenByDescending { it.second.salesCount }
            )
            .distinctBy { it.second.institutionId }
            .take(requestedCount)

        val offeringInstitutions = if (reportTarget == ReportTarget.INSTITUTION) {
            institutionRepository.findAllById(discoveredOfferings.map { it.second.institutionId })
        } else emptyList()
        val institutionCandidates = if (offeringInstitutions.isNotEmpty()) offeringInstitutions else unifiedSearch.institutions
        val institutions = institutionCandidates
            .distinctBy { it.id }
            .sortedByDescending { it.rating }
            .take(4)
        val relatedDoctorIds = if (reportTarget == ReportTarget.DOCTOR && discoveredProjects.isNotEmpty()) {
            val discoveredProjectIds = discoveredProjects.map { it.id }.toSet()
            val offeringIds = discoveredOfferings.map { it.second.id }.toSet()
            doctorProjectRepository.findAll()
                .filter { it.projectId in discoveredProjectIds || it.institutionProjectId in offeringIds }
                .map { it.doctorId }
                .distinct()
        } else emptyList()
        val relatedDoctors = if (relatedDoctorIds.isNotEmpty()) doctorRepository.findAllById(relatedDoctorIds) else emptyList()
        val doctorCandidates = when {
            relatedDoctors.isNotEmpty() -> relatedDoctors
            reportTarget == ReportTarget.DOCTOR && mentionedCities.isNotEmpty() &&
                RequestedEntityType.TREATMENT !in explicitlyRequestedTypes -> doctorRepository.findAll()
            else -> unifiedSearch.doctors
        }
        val doctorInstitutionCities = institutionRepository.findAll().associate { it.id to it.city }
        val doctors = doctorCandidates
            .filter { doctor ->
                mentionedCities.isEmpty() || mentionedCities.any { city ->
                    doctorInstitutionService.findByDoctorId(doctor.id).any { relation ->
                        doctorInstitutionCities[relation.institutionId]?.equals(city, true) == true
                    }
                }
            }
            .sortedByDescending { it.rating }
            .take(4)
        val searchedProjectEntities = projectRepository.findAllById(unifiedSearch.projects.map { it.id })
            .associateBy { it.id }
        val projects = unifiedSearch.projects.mapNotNull { searchedProjectEntities[it.id] }.take(4)

        val institutionIds = institutions.map { it.id }.toSet()
        val projectIds = (projects.map { it.id } + discoveredProjects.map { it.id }).toSet()
        val discoveredOfferingIds = discoveredOfferings.map { it.second.id }.toSet()
        val explicitlyNamedInstitutionIds = institutions
            .filter { it.name.isNotBlank() && effectiveSearchQuery.contains(it.name, true) }
            .map { it.id }
            .toSet()
        val allInstitutionProjects = institutionProjectRepository.findAll().filter { it.isActive }
        val allInstitutions = institutionRepository.findAll().associateBy { it.id }
        val allProjectsById = projectRepository.findAll().associateBy { it.id }
        val directSearchTerms = discoverSearchService.extractMatchingFragments(effectiveSearchQuery)
        val directlyMatchedProjectIds = projectRepository.findAll()
            .filter { project ->
                val searchable = "${project.name} ${project.category} ${project.tags} ${project.categoryTags}".lowercase()
                effectiveSearchQuery.contains(project.name, true) ||
                    directSearchTerms.any { searchable.contains(it.lowercase()) }
            }
            .map { it.id }
            .toSet()
        val directlyMatchedInstitutionProjects = allInstitutionProjects.filter { offering ->
                val project = allProjectsById[offering.projectId]
                val institution = allInstitutions[offering.institutionId]
                project != null &&
                    (offering.projectId in directlyMatchedProjectIds || offeringMatchesQuery(
                        query = effectiveSearchQuery,
                        terms = directSearchTerms,
                        offering = offering,
                        project = project
                    )) &&
                    (mentionedCities.isEmpty() || mentionedCities.any { city -> institution?.city?.equals(city, true) == true })
            }
        val directlyMatchedInstitutionProjectIds = directlyMatchedInstitutionProjects.map { it.id }.toSet()
        val institutionProjects = when {
            reportTarget != ReportTarget.DOCTOR && directlyMatchedInstitutionProjects.isNotEmpty() ->
                directlyMatchedInstitutionProjects.take(8)
            discoveredOfferingIds.isNotEmpty() -> allInstitutionProjects.filter { it.id in discoveredOfferingIds }
            else -> allInstitutionProjects.filter {
                it.isActive && when {
                    projectIds.isNotEmpty() && institutionIds.isNotEmpty() -> it.projectId in projectIds && it.institutionId in institutionIds
                    projectIds.isNotEmpty() -> it.projectId in projectIds
                    explicitlyNamedInstitutionIds.isNotEmpty() -> it.institutionId in explicitlyNamedInstitutionIds
                    else -> effectiveSearchQuery.contains(it.id, true)
                }
            }.take(8)
        }
        val institutionById = institutionRepository.findAllById(institutionProjects.map { it.institutionId }).associateBy { it.id }
        val projectById = projectRepository.findAllById(institutionProjects.map { it.projectId }).associateBy { it.id }

        val items = buildList {
            institutions.forEach { institution ->
                add(AgentCatalogItemResponse(
                    type = "INSTITUTION", id = institution.id, name = institution.name,
                    subtitle = institution.city,
                    summary = institution.description,
                    attributes = linkedMapOf(
                        AgentText.value("评分", "Rating") to institution.rating.toPlainString(),
                        AgentText.value("评价数", "Reviews") to institution.reviewCount.toString(),
                        AgentText.value("认证", "Verified") to AgentText.value(if (institution.isVerified) "已认证" else "未认证", if (institution.isVerified) "Verified" else "Not verified"),
                        AgentText.value("医生数", "Doctors") to institution.doctorCount.toString(),
                        AgentText.value("特色", "Specialties") to institution.specialties
                    ).filterValues { it.isNotBlank() },
                    institutionId = institution.id, canChatWithHuman = true
                ))
            }
            doctors.forEach { doctor ->
                val doctorInstitutions = doctorInstitutionService.institutionsFor(doctor.id)
                val selectedInstitution = doctorInstitutions.firstOrNull { institution ->
                    institution.name.isNotBlank() && effectiveSearchQuery.contains(institution.name, ignoreCase = true) ||
                        institution.city.isNotBlank() && effectiveSearchQuery.contains(institution.city, ignoreCase = true)
                } ?: doctorInstitutions.firstOrNull()
                val institutionLabel = doctorInstitutions.take(2).joinToString("、") { it.name } +
                    if (doctorInstitutions.size > 2) AgentText.value("等${doctorInstitutions.size}家", " +${doctorInstitutions.size - 2}") else ""
                add(AgentCatalogItemResponse(
                    type = "DOCTOR", id = doctor.id, name = doctor.name,
                    subtitle = listOf(doctor.title, institutionLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    summary = doctor.bio,
                    attributes = linkedMapOf(
                        AgentText.value("评分", "Rating") to doctor.rating.toPlainString(),
                        AgentText.value("评价数", "Reviews") to doctor.reviewCount.toString(),
                        AgentText.value("认证", "Verified") to AgentText.value(if (doctor.isVerified) "已认证" else "未认证", if (doctor.isVerified) "Verified" else "Not verified"),
                        AgentText.value("专长", "Specialties") to doctor.specialties,
                        AgentText.value("资质", "Credentials") to doctor.credentials,
                        AgentText.value("出诊机构", "Clinics") to doctorInstitutions.joinToString("、") { it.name }
                    ).filterValues { it.isNotBlank() },
                    institutionId = selectedInstitution?.id,
                    canChatWithHuman = selectedInstitution != null
                ))
            }
            projects.forEach { project ->
                add(AgentCatalogItemResponse(
                    type = "PROJECT", id = project.id, name = project.name, subtitle = project.category,
                    summary = project.description,
                    attributes = linkedMapOf(
                        AgentText.value("参考价", "Reference price") to "¥${project.referencePrice.toPlainString()}",
                        AgentText.value("评分", "Rating") to project.rating.toPlainString(),
                        AgentText.value("标签", "Tags") to project.tags
                    ).filterValues { it.isNotBlank() },
                    projectId = project.id
                ))
            }
            institutionProjects.forEach { offering ->
                val institution = institutionById[offering.institutionId] ?: return@forEach
                val project = projectById[offering.projectId] ?: return@forEach
                val effective = institutionProjectDetailResolver.resolve(offering, project)
                add(AgentCatalogItemResponse(
                    type = "INSTITUTION_PROJECT", id = offering.id, name = "${institution.name} · ${effective.name}",
                    subtitle = listOf(institution.city, effective.category).filter { it.isNotBlank() }.joinToString(" · "),
                    summary = effective.description,
                    attributes = linkedMapOf(
                        AgentText.value("机构价格", "Clinic price") to "¥${offering.price.toPlainString()}",
                        AgentText.value("项目参考价", "Reference price") to "¥${project.referencePrice.toPlainString()}",
                        AgentText.value("评分", "Rating") to effective.rating.toPlainString(),
                        AgentText.value("评价数", "Review count") to effective.reviewCount.toString(),
                        AgentText.value("标签", "Tags") to effective.tags,
                        AgentText.value("宣传语", "Slogan") to effective.slogan,
                        AgentText.value("详情摘要", "Detail summary") to catalogDetailSummary(effective.detailContent),
                        AgentText.value("销量", "Sales") to offering.salesCount.toString(),
                        AgentText.value("机构认证", "Clinic verified") to AgentText.value(if (institution.isVerified) "已认证" else "未认证", if (institution.isVerified) "Verified" else "Not verified")
                    ).filterValues { it.isNotBlank() },
                    institutionId = institution.id, projectId = project.id, canChatWithHuman = true
                ))
            }
        }.distinctBy { "${it.type}:${it.id}" }
            .filter { item ->
                reportTarget == null || item.type == reportTarget.itemType ||
                    (item.type == "INSTITUTION_PROJECT" && item.id in directlyMatchedInstitutionProjectIds &&
                        (reportTarget == ReportTarget.PROJECT || reportTarget == ReportTarget.INSTITUTION ||
                            reportTarget == ReportTarget.INSTITUTION_PROJECT))
            }
            .sortedBy { if (mode == "COMPARISON" && it.type == "INSTITUTION_PROJECT") 0 else 1 }
            .take(12)
        val summary = if (items.isEmpty()) {
            AgentText.value("数据库中没有找到与该问题明确匹配的记录，请提供机构、医生或项目名称。", "No clearly matching database records were found. Provide a clinic, doctor, or treatment name.")
        } else if (mode == "COMPARISON") {
            AgentText.value("已从平台数据库提取 ${items.size} 条可核验记录进行横向比较。", "${items.size} verifiable platform records were retrieved for comparison.")
        } else {
            AgentText.value("已汇总平台数据库中的 ${items.size} 条相关记录。", "${items.size} related platform records were summarized.")
        }
        return AgentCatalogReportResponse(
            mode = mode,
            title = reportTitle(mode, reportTarget),
            summary = summary,
            items = items,
            comparisonDimensions = comparisonDimensions(reportTarget),
            warnings = listOf(AgentText.value("平台数据仅用于信息比较，不代表医疗适用性或效果保证。", "Platform data supports information comparison only and does not establish medical suitability or guarantee results."))
        )
    }

    fun promptContext(query: String): String {
        return promptEvidence(query).context
    }

    /**
     * Completes an elliptical follow-up with stable filters from recent user turns.
     * Entity type words are intentionally not copied, so the current question still
     * decides whether the result should contain doctors, clinics, or treatments.
     */
    fun contextualSearchQuery(query: String, previousUserQueries: List<String>): String {
        val currentKeywords = catalogContextKeywords(query)
        val supplements = linkedSetOf<String>()
        val recentKeywordSets = previousUserQueries.asReversed().map(::catalogContextKeywords)

        if (currentKeywords.cities.isEmpty()) {
            recentKeywordSets.firstNotNullOfOrNull { it.cities.firstOrNull() }?.let(supplements::add)
        }

        val isEllipticalFollowUp = listOf(
            "哪些", "有哪些", "还有", "相关", "推荐", "这个", "这家", "该", "它", "对比", "比较", "总结", "报告",
            "which", "what", "who", "more", "related", "this", "it", "compare", "comparison", "summary", "report"
        ).any { query.contains(it, true) }
        val previousCity = recentKeywordSets.firstNotNullOfOrNull { it.cities.firstOrNull() }
        val switchesCity = currentKeywords.cities.isNotEmpty() && previousCity != null &&
            currentKeywords.cities.none { it.equals(previousCity, true) }
        val nearestPrevious = recentKeywordSets.firstOrNull { it.all.isNotEmpty() }
        val nearestPreviousSubject = recentKeywordSets.firstOrNull {
            (it.projects + it.categories + it.tags + it.institutions + it.doctors + it.offerings + it.searchTerms).isNotEmpty()
        }
        val currentSubjects = currentKeywords.projects + currentKeywords.categories + currentKeywords.tags +
            currentKeywords.institutions + currentKeywords.doctors + currentKeywords.offerings + currentKeywords.searchTerms
        val previousSubjects = nearestPreviousSubject?.let {
            it.projects + it.categories + it.tags + it.institutions + it.doctors + it.offerings + it.searchTerms
        }.orEmpty()
        val switchesSubject = currentSubjects.isNotEmpty() && previousSubjects.isNotEmpty() &&
            currentSubjects.none { current -> previousSubjects.any { it.equals(current, true) } }
        val explicitlyContinuesPreviousSubject = listOf(
            "那", "呢", "同样", "换成", "这个城市", "该城市", "how about", "what about", "same", "instead"
        ).any { query.contains(it, true) }
        val startsNewTopic = (switchesCity || switchesSubject) && !explicitlyContinuesPreviousSubject
        val shouldInheritSubjectKeywords = isEllipticalFollowUp && !startsNewTopic

        if (shouldInheritSubjectKeywords) {
            nearestPreviousSubject?.let { keywords ->
                if (currentKeywords.projects.isEmpty()) supplements += keywords.projects
                if (currentKeywords.categories.isEmpty()) supplements += keywords.categories
                if (currentKeywords.tags.isEmpty()) supplements += keywords.tags
                if (currentKeywords.institutions.isEmpty()) supplements += keywords.institutions
                if (currentKeywords.doctors.isEmpty()) supplements += keywords.doctors
                if (currentKeywords.offerings.isEmpty()) supplements += keywords.offerings
                if (currentKeywords.searchTerms.isEmpty()) supplements += keywords.searchTerms
            }
        }
        if (detectReportTarget(query) == null && explicitlyContinuesPreviousSubject) {
            previousUserQueries.asReversed()
                .firstNotNullOfOrNull { previous -> detectReportTarget(previous) }
                ?.let { previousTarget -> supplements += previousTarget.queryTerm }
        }
        return (listOf(query.trim()) + supplements).filter { it.isNotBlank() }.distinct().joinToString(" ")
    }

    private fun catalogContextKeywords(query: String): CatalogContextKeywords {
        val projects = projectRepository.findAll()
        val institutions = institutionRepository.findAll()
        val doctors = doctorRepository.findAll()
        val projectMap = projects.associateBy { it.id }
        val institutionMap = institutions.associateBy { it.id }
        val effectiveOfferings = institutionProjectRepository.findAll().asSequence()
            .filter { it.isActive }
            .mapNotNull { offering ->
                val project = projectMap[offering.projectId] ?: return@mapNotNull null
                EffectiveCatalogOffering(
                    baseProject = project,
                    institution = institutionMap[offering.institutionId],
                    detail = institutionProjectDetailResolver.resolve(offering, project)
                )
            }
            .toList()
        val matchedProjects = projects.filter { it.name.length >= 2 && query.contains(it.name, true) }
        val matchedInstitutions = institutions.filter { it.name.length >= 2 && query.contains(it.name, true) }
        val matchedOfferings = effectiveOfferings.filter { effectiveOffering ->
            offeringAtomicValues(effectiveOffering.detail, effectiveOffering.baseProject)
                .any { it.length >= 2 && query.contains(it, true) }
        }
        return CatalogContextKeywords(
            cities = discoverSearchService.citiesMentionedIn(query).toSet(),
            projects = (matchedProjects.map { it.name } + matchedOfferings.map { it.detail.name }).toSet(),
            institutions = matchedInstitutions.map { it.name }.toSet(),
            doctors = doctors.filter { it.name.length >= 2 && query.contains(it.name, true) }.map { it.name }.toSet(),
            categories = (projects.map { it.category.trim() } + effectiveOfferings.map { it.detail.category.trim() })
                .filter { it.length >= 2 && query.contains(it, true) }.toSet(),
            tags = (projects.flatMap { it.tags.split(",", "，") } + effectiveOfferings.flatMap { it.detail.tags.split(",", "，") }).map { it.trim() }
                .filter { it.length >= 2 && query.contains(it, true) }.toSet(),
            offerings = matchedOfferings.map { effectiveOffering ->
                listOfNotNull(effectiveOffering.institution?.name, effectiveOffering.detail.name).joinToString(" ")
            }.toSet(),
            searchTerms = discoverSearchService.extractMatchingFragments(query)
        )
    }

    private fun offeringMatchesQuery(
        query: String,
        terms: Collection<String>,
        offering: InstitutionProjectEntity,
        project: ProjectEntity
    ): Boolean {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.length < 2) return false
        val effective = institutionProjectDetailResolver.resolve(offering, project)
        val atomicValues = offeringAtomicValues(effective, project)
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
        val searchable = buildString {
            append(atomicValues.joinToString(" "))
            append(' ').append(effective.description)
            append(' ').append(effective.slogan)
            append(' ').append(catalogDetailSummary(effective.detailContent))
        }.lowercase()
        val normalizedTerms = terms.map { it.trim().lowercase() }.filter { it.length >= 2 }
        return atomicValues.any(normalizedQuery::contains) ||
            searchable.contains(normalizedQuery) || normalizedTerms.any(searchable::contains)
    }

    private fun offeringAtomicValues(
        effective: ProjectEntity,
        project: ProjectEntity?
    ): List<String> = buildList {
        add(effective.name)
        add(effective.category)
        addAll(effective.tags.split(",", "，"))
        add(effective.slogan)
        project?.let {
            add(it.name)
            add(it.category)
            addAll(it.tags.split(",", "，"))
        }
    }.map(String::trim).filter(String::isNotBlank)

    fun promptEvidence(
        query: String,
        searchQuery: String = query,
        targetQuery: String = query,
        queryTarget: AgentQueryTarget? = null
    ): AgentPromptEvidence {
        val detectedCities = discoverSearchService.citiesMentionedIn(searchQuery)
        val detectedKeywords = catalogContextKeywords(searchQuery).all.toList()
        val explicitlyRequestedTypes = discoverSearchService.explicitlyRequestedEntityTypes(query)
        val reportTarget = queryTarget?.toReportTarget() ?: detectReportTarget(targetQuery)
        val report = report(
            request = AgentCatalogReportRequest(targetQuery),
            mentionedCities = detectedCities,
            explicitlyRequestedTypes = explicitlyRequestedTypes,
            searchQuery = searchQuery,
            targetOverride = queryTarget
        )
        val matchedRequestedTypes = explicitlyRequestedTypes.filterTo(linkedSetOf()) { requestedType ->
            when (requestedType) {
                RequestedEntityType.TREATMENT -> report.items.any { it.type == "PROJECT" || it.type == "INSTITUTION_PROJECT" } ||
                    (reportTarget in setOf(ReportTarget.INSTITUTION, ReportTarget.DOCTOR) && report.items.isNotEmpty())
                RequestedEntityType.DOCTOR -> report.items.any { it.type == "DOCTOR" }
                RequestedEntityType.INSTITUTION -> report.items.any { it.type == "INSTITUTION" || it.type == "INSTITUTION_PROJECT" }
            }
        }
        val missingRequestedTypes = explicitlyRequestedTypes - matchedRequestedTypes
        if (report.items.isEmpty() || missingRequestedTypes.isNotEmpty()) {
            val context = if (explicitlyRequestedTypes.isNotEmpty()) {
                """
                    Platform database search result: one or more specifically named entities were not found. Requested types: ${explicitlyRequestedTypes.joinToString()}. Missing types: ${missingRequestedTypes.joinToString()}.
                    Instruction: Do not substitute unrelated popular treatments, doctors, clinics, or platform records. Say briefly that the specified item is not currently available. Add at most one useful general point and, only when helpful, invite the user to ask about another item. Do not produce a checklist or comprehensive explanation.
                """.trimIndent()
            } else {
                "Platform database search result: no direct match was found. Give one concise useful answer, then ask at most one short clarifying question only if needed. Do not invent platform records or give a comprehensive checklist."
            }
            return AgentPromptEvidence(
                context = context,
                detectedCities = detectedCities,
                detectedKeywords = detectedKeywords,
                noMatch = true,
                explicitlyRequestedEntityTypes = explicitlyRequestedTypes.map { it.name }.toSet(),
                missingRequestedEntityTypes = missingRequestedTypes.map { it.name }.toSet(),
                report = report.takeIf { it.items.isNotEmpty() }
            )
        }
        val evidence = report.items.take(5).joinToString("\n") { item ->
            "[${item.type}] ${item.name}; ${item.subtitle}; ${item.attributes.entries.take(5).joinToString { "${it.key}=${it.value}" }}; ${item.summary.take(120)}"
        }
        val context = """
            Platform database search results:
            $evidence
            Instruction: The structured report displays the detailed records. In the conversational answer, summarize only the 1-2 most useful findings in 2-4 sentences. Do not repeat every clinic, doctor, price, rating, credential, or attribute. For comparisons, mention only the clearest difference and a short choice direction; wait for the user to ask before expanding. Do not claim that names are missing when candidates are present.
        """.trimIndent()
        return AgentPromptEvidence(
            context = context,
            detectedCities = detectedCities,
            detectedKeywords = detectedKeywords,
            matchedEntityIds = report.items.groupBy { it.type }.mapValues { (_, items) -> items.map { it.id } },
            noMatch = false,
            explicitlyRequestedEntityTypes = explicitlyRequestedTypes.map { it.name }.toSet(),
            report = report
        )
    }

    private fun requestedInstitutionCount(query: String): Int {
        Regex("(\\d+)\\s*(家|所|clinics?|institutions?)", RegexOption.IGNORE_CASE)
            .find(query)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it.coerceIn(2, 4) }
        return when {
            listOf("四家", "四所", "four clinics", "four institutions").any { query.contains(it, true) } -> 4
            listOf("三家", "三所", "three clinics", "three institutions").any { query.contains(it, true) } -> 3
            else -> 2
        }
    }

    private fun detectReportTarget(query: String): ReportTarget? = when {
        listOf("机构项目", "机构套餐", "项目套餐", "套餐", "报价", "institution project", "clinic package", "package", "offering")
            .any { query.contains(it, true) } -> ReportTarget.INSTITUTION_PROJECT
        listOf("医生", "医师", "大夫", "doctor", "surgeon", "physician")
            .any { query.contains(it, true) } -> ReportTarget.DOCTOR
        listOf("机构", "医院", "诊所", "门诊部", "clinic", "hospital", "institution")
            .any { query.contains(it, true) } -> ReportTarget.INSTITUTION
        listOf("项目", "治疗", "术式", "procedure", "treatment")
            .any { query.contains(it, true) } -> ReportTarget.PROJECT
        else -> null
    }

    private fun catalogDetailSummary(content: String?): String = content.orEmpty()
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)

    private fun AgentQueryTarget.toReportTarget(): ReportTarget = ReportTarget.valueOf(name)

    private fun reportTitle(mode: String, target: ReportTarget?): String {
        val targetZh = when (target) {
            ReportTarget.INSTITUTION -> "机构"
            ReportTarget.DOCTOR -> "医生"
            ReportTarget.PROJECT -> "项目"
            ReportTarget.INSTITUTION_PROJECT -> "机构项目"
            null -> "数据库"
        }
        val targetEn = when (target) {
            ReportTarget.INSTITUTION -> "Clinic"
            ReportTarget.DOCTOR -> "Doctor"
            ReportTarget.PROJECT -> "Treatment"
            ReportTarget.INSTITUTION_PROJECT -> "Clinic treatment"
            null -> "Database"
        }
        return AgentText.value(
            "$targetZh${if (mode == "COMPARISON") "对比报告" else "总结报告"}",
            "$targetEn ${if (mode == "COMPARISON") "comparison" else "summary"} report"
        )
    }

    private fun comparisonDimensions(target: ReportTarget?): List<String> = when (target) {
        ReportTarget.INSTITUTION -> listOf("资质与认证" to "Credentials", "评分与评价量" to "Ratings and reviews", "特色与项目匹配" to "Specialty fit")
        ReportTarget.DOCTOR -> listOf("资质与职称" to "Credentials and title", "专长匹配" to "Specialty fit", "评分与案例" to "Ratings and cases")
        ReportTarget.PROJECT -> listOf("项目定位" to "Treatment purpose", "参考价格" to "Reference price", "适用诉求" to "Suitable concerns")
        ReportTarget.INSTITUTION_PROJECT -> listOf("机构价格" to "Clinic price", "销量" to "Sales", "机构认证" to "Clinic verification")
        null -> listOf("资质与认证" to "Credentials", "价格" to "Price", "评分与评价量" to "Ratings and reviews")
    }.map { (zh, en) -> AgentText.value(zh, en) }

}

private enum class ReportTarget(val itemType: String, val queryTerm: String) {
    INSTITUTION("INSTITUTION", "机构"),
    DOCTOR("DOCTOR", "医生"),
    PROJECT("PROJECT", "项目"),
    INSTITUTION_PROJECT("INSTITUTION_PROJECT", "机构项目")
}

data class AgentPromptEvidence(
    val context: String = "",
    val detectedCities: List<String> = emptyList(),
    val detectedKeywords: List<String> = emptyList(),
    val matchedEntityIds: Map<String, List<String>> = emptyMap(),
    val noMatch: Boolean = false,
    val explicitlyRequestedEntityTypes: Set<String> = emptySet(),
    val missingRequestedEntityTypes: Set<String> = emptySet(),
    val report: AgentCatalogReportResponse? = null
)
