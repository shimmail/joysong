package com.joysong.server.agent.service

import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportRequest
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.dto.AgentProfileResponse
import com.joysong.server.discover.dto.InstitutionProjectItemResponse
import com.joysong.server.discover.dto.ProjectWithInstitutionsResponse
import com.joysong.server.discover.service.DiscoverSearchRequest
import com.joysong.server.discover.service.DiscoverSearchResult
import com.joysong.server.discover.service.DiscoverKeywordExtractor
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.repository.PublicDoctorProjectView
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.discover.service.RequestedEntityType
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale

class AgentCatalogServiceTest {
    private val institutionRepository = mockk<InstitutionRepository>(relaxed = true)
    private val doctorRepository = mockk<DoctorRepository>(relaxed = true)
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>(relaxed = true)
    private val doctorProjectRepository = mockk<DoctorProjectRepository>(relaxed = true)
    private val discoverSearchService = mockk<DiscoverSearchService>(relaxed = true)
    private val doctorInstitutionService = mockk<DoctorInstitutionService>(relaxed = true)
    private val institutionConsultantService = mockk<InstitutionConsultantService>(relaxed = true)
    private val agentProfileService = mockk<AgentProfileService>(relaxed = true)
    private val service = AgentCatalogService(
        institutionRepository,
        doctorRepository,
        projectRepository,
        institutionProjectRepository,
        doctorProjectRepository,
        discoverSearchService,
        doctorInstitutionService,
        InstitutionProjectDetailResolver(),
        institutionConsultantService,
        agentProfileService
    )

    private fun publicBinding(
        doctorId: String,
        institutionProjectId: String,
        projectId: String,
        price: String
    ): PublicDoctorProjectView = mockk<PublicDoctorProjectView>().also { binding ->
        every { binding.doctorId } returns doctorId
        every { binding.projectId } returns projectId
        every { binding.institutionProjectId } returns institutionProjectId
        every { binding.price } returns BigDecimal(price)
    }

    private fun stubPublicOfferings(offerings: List<InstitutionProjectEntity>) {
        every { institutionProjectRepository.findAll() } returns offerings
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(offerings.map { it.id })
        } returns offerings.map { offering ->
            publicBinding(
                doctorId = "doctor-${offering.id}",
                institutionProjectId = offering.id,
                projectId = offering.projectId,
                price = offering.price.toPlainString()
            )
        }
    }

    @BeforeEach
    fun useChineseLocale() {
        LocaleContextHolder.setLocale(Locale.CHINESE)
    }

    @AfterEach
    fun resetLocale() {
        LocaleContextHolder.resetLocaleContext()
    }

    @Test
    fun `consultable institutions accumulate exact city profile and nationwide tiers stably`() {
        val institutions = listOf(
            institution("explicit-low", "星颜医疗美容医院", "北京", "4.1"),
            institution("shanghai-high", "上海优选", "上海", "4.9"),
            institution("shanghai-low", "上海安心", "上海", "4.2"),
            institution("national-high", "全国优选", "广州", "4.8")
        )
        stubConsultableInstitutions(institutions, institutions.map { it.id }.toSet(), profileCity = "上海")

        val result = service.selectConsultableInstitutions("user-1", "我想联系星颜医疗美容医院咨询")

        assertEquals(
            listOf("explicit-low", "shanghai-high", "shanghai-low", "national-high"),
            result.items.map { it.id }
        )
        assertTrue(result.items.all {
            it.type == "INSTITUTION" &&
                it.institutionId == it.id &&
                it.canChatWithHuman
        })
        verify(exactly = 1) { institutionConsultantService.listConsultableInstitutionIds() }
        verify(exactly = 0) { institutionConsultantService.listApprovedConsultants(any()) }
    }

    @Test
    fun `consultable institutions exclude unverified and unavailable active rows`() {
        val institutions = listOf(
            institution("eligible", "可咨询机构", "上海", "4.2"),
            institution("unverified", "未认证机构", "上海", "4.9", isVerified = false),
            institution("unavailable", "无顾问机构", "上海", "4.8")
        )
        stubConsultableInstitutions(institutions, setOf("eligible", "unverified"))

        val result = service.selectConsultableInstitutions("user-1", "真人咨询")

        assertEquals(listOf("eligible"), result.items.map { it.id })
    }

    @Test
    fun `profile lookup failure falls back nationwide`() {
        val institutions = listOf(
            institution("nation-low", "全国安心", "北京", "4.2"),
            institution("nation-high", "全国优选", "广州", "4.9")
        )
        every { institutionRepository.findAll() } returns institutions
        every { institutionConsultantService.listConsultableInstitutionIds() } returns institutions.map { it.id }.toSet()
        every { discoverSearchService.citiesMentionedIn(any()) } returns emptyList()
        every { agentProfileService.get("user-1") } throws IllegalStateException("profile unavailable")

        val result = service.selectConsultableInstitutions("user-1", "真人咨询")

        assertEquals(listOf("nation-high", "nation-low"), result.items.map { it.id })
    }

    @Test
    fun `consultable institutions use rating then id and stop at four`() {
        val institutions = listOf(
            institution("c", "机构C", "北京", "4.8"),
            institution("b", "机构B", "北京", "4.9"),
            institution("a", "机构A", "北京", "4.9"),
            institution("d", "机构D", "北京", "4.7"),
            institution("e", "机构E", "北京", "4.6")
        )
        stubConsultableInstitutions(institutions, institutions.map { it.id }.toSet())

        val result = service.selectConsultableInstitutions("user-1", "真人咨询")

        assertEquals(listOf("a", "b", "c", "d"), result.items.map { it.id })
    }

    @Test
    fun `unavailable named institution returns eligible alternatives`() {
        val institutions = listOf(
            institution("unavailable", "星颜医疗美容医院", "上海", "4.9"),
            institution("alternative", "可咨询机构", "北京", "4.4")
        )
        stubConsultableInstitutions(institutions, setOf("alternative"))

        val result = service.selectConsultableInstitutions("user-1", "星颜医疗美容医院")

        assertTrue(result.requestedInstitutionUnavailable)
        assertEquals(listOf("alternative"), result.items.map { it.id })
    }

    @Test
    fun `soft deleted named institution suppresses stale detail context and returns active alternatives`() {
        val context = institution("context", "杭州安心", "杭州", "4.1")
        val alternatives = (1..4).map { index ->
            institution(
                id = "alternative-$index",
                name = "上海优选$index",
                city = "上海",
                rating = "4.${10 - index}"
            )
        }
        val institutions = listOf(context) + alternatives
        every { institutionRepository.findAll() } returns institutions
        every { institutionConsultantService.listConsultableInstitutionIds() } returns institutions.map { it.id }.toSet()
        every { agentProfileService.get("user-1") } returns profile("上海")
        every {
            institutionRepository.countSoftDeletedNamesMentionedInQuery("我想联系星颜做真人咨询")
        } returns 1
        val realDiscoverSearchService = DiscoverSearchService(
            projectRepository = projectRepository,
            institutionRepository = institutionRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorRepository = doctorRepository,
            doctorProjectRepository = doctorProjectRepository,
            keywordExtractor = DiscoverKeywordExtractor(),
            doctorInstitutionService = doctorInstitutionService,
            institutionProjectDetailResolver = InstitutionProjectDetailResolver()
        )
        val localService = AgentCatalogService(
            institutionRepository,
            doctorRepository,
            projectRepository,
            institutionProjectRepository,
            doctorProjectRepository,
            realDiscoverSearchService,
            doctorInstitutionService,
            InstitutionProjectDetailResolver(),
            institutionConsultantService,
            agentProfileService
        )

        val result = localService.selectConsultableInstitutions(
            userId = "user-1",
            query = "我想联系星颜做真人咨询",
            contextInstitutionId = context.id
        )

        assertTrue(result.requestedInstitutionUnavailable)
        assertEquals(alternatives.map { it.id }, result.items.map { it.id })
        assertFalse(result.items.any { it.id == context.id })
        verify(exactly = 1) {
            institutionRepository.countSoftDeletedNamesMentionedInQuery("我想联系星颜做真人咨询")
        }
    }

    @Test
    fun `eligible detail context precedes profile only when current text has no institution or city`() {
        val institutions = listOf(
            institution("context", "上下文机构", "杭州", "4.1"),
            institution("profile", "上海优选", "上海", "4.9"),
            institution("named", "星颜医疗美容医院", "北京", "4.3")
        )
        stubConsultableInstitutions(institutions, institutions.map { it.id }.toSet(), profileCity = "上海")
        every { discoverSearchService.citiesMentionedIn("北京真人咨询") } returns listOf("北京")
        every { discoverSearchService.hasNamedInstitutionPhrase("未知医疗美容医院") } returns true

        val withoutCurrentInstitutionOrCity = service.selectConsultableInstitutions("user-1", "真人咨询", "context")
        val withCurrentInstitution = service.selectConsultableInstitutions("user-1", "星颜医疗美容医院", "context")
        val withCurrentCity = service.selectConsultableInstitutions("user-1", "北京真人咨询", "context")
        val withUnknownInstitution = service.selectConsultableInstitutions("user-1", "未知医疗美容医院", "context")

        assertEquals("context", withoutCurrentInstitutionOrCity.items.first().id)
        assertEquals("named", withCurrentInstitution.items.first().id)
        assertEquals("named", withCurrentCity.items.first().id)
        assertEquals("profile", withUnknownInstitution.items.first().id)
    }

    @Test
    fun `generic handoff wording keeps eligible detail context ahead of higher rated profile candidates`() {
        val context = institution("context", "杭州安心", "杭州", "4.1")
        val profileCandidates = (1..4).map { index ->
            institution(
                id = "profile-$index",
                name = "上海优选$index",
                city = "上海",
                rating = "4.${10 - index}"
            )
        }
        val institutions = listOf(context) + profileCandidates
        every { institutionRepository.findAll() } returns institutions
        every { institutionConsultantService.listConsultableInstitutionIds() } returns institutions.map { it.id }.toSet()
        every { agentProfileService.get("user-1") } returns profile("上海")
        val realDiscoverSearchService = DiscoverSearchService(
            projectRepository = projectRepository,
            institutionRepository = institutionRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorRepository = doctorRepository,
            doctorProjectRepository = doctorProjectRepository,
            keywordExtractor = DiscoverKeywordExtractor(),
            doctorInstitutionService = doctorInstitutionService,
            institutionProjectDetailResolver = InstitutionProjectDetailResolver()
        )
        val localService = AgentCatalogService(
            institutionRepository,
            doctorRepository,
            projectRepository,
            institutionProjectRepository,
            doctorProjectRepository,
            realDiscoverSearchService,
            doctorInstitutionService,
            InstitutionProjectDetailResolver(),
            institutionConsultantService,
            agentProfileService
        )

        val result = localService.selectConsultableInstitutions(
            userId = "user-1",
            query = "我想找真人咨询的医美机构",
            contextInstitutionId = context.id
        )

        assertEquals(listOf("context", "profile-1", "profile-2", "profile-3"), result.items.map { it.id })
        assertFalse(result.requestedInstitutionUnavailable)
    }

    @Test
    fun `deictic institution polarity controls detail context without unavailable copy`() {
        val context = institution("context", "杭州安心", "杭州", "4.1")
        val alternatives = (1..4).map { index ->
            institution(
                id = "alternative-$index",
                name = "上海优选$index",
                city = "上海",
                rating = "4.${10 - index}"
            )
        }
        val institutions = listOf(context) + alternatives
        every { institutionRepository.findAll() } returns institutions
        every { institutionRepository.countSoftDeletedNamesMentionedInQuery(any()) } returns 0
        every { institutionConsultantService.listConsultableInstitutionIds() } returns institutions.map { it.id }.toSet()
        every { agentProfileService.get("user-1") } returns profile("上海")
        val realDiscoverSearchService = DiscoverSearchService(
            projectRepository = projectRepository,
            institutionRepository = institutionRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorRepository = doctorRepository,
            doctorProjectRepository = doctorProjectRepository,
            keywordExtractor = DiscoverKeywordExtractor(),
            doctorInstitutionService = doctorInstitutionService,
            institutionProjectDetailResolver = InstitutionProjectDetailResolver()
        )
        val localService = AgentCatalogService(
            institutionRepository,
            doctorRepository,
            projectRepository,
            institutionProjectRepository,
            doctorProjectRepository,
            realDiscoverSearchService,
            doctorInstitutionService,
            InstitutionProjectDetailResolver(),
            institutionConsultantService,
            agentProfileService
        )

        val chinese = localService.selectConsultableInstitutions(
            "user-1",
            "我想咨询这家诊所的真人顾问",
            context.id
        )
        val english = localService.selectConsultableInstitutions(
            "user-1",
            "talk to a specialist at that clinic",
            context.id
        )
        val negated = localService.selectConsultableInstitutions(
            "user-1",
            "不要这家诊所，我想找真人顾问",
            context.id
        )
        val englishNegated = localService.selectConsultableInstitutions(
            "user-1",
            "do not use that clinic; talk to a specialist",
            context.id
        )

        listOf(chinese, english).forEach { result ->
            assertEquals(context.id, result.items.first().id)
            assertFalse(result.requestedInstitutionUnavailable)
        }
        listOf(negated, englishNegated).forEach { result ->
            assertEquals(alternatives.map { it.id }, result.items.map { it.id })
            assertFalse(result.items.any { it.id == context.id })
            assertFalse(result.requestedInstitutionUnavailable)
        }
    }

    @Test
    fun `empty consultable institution set returns an empty selection`() {
        stubConsultableInstitutions(listOf(institution("clinic", "可咨询机构", "上海", "4.8")), emptySet())

        val result = service.selectConsultableInstitutions("user-1", "真人咨询")

        assertTrue(result.items.isEmpty())
        assertFalse(result.requestedInstitutionUnavailable)
    }

    @Test
    fun `institution project matching uses both inherited and overridden details`() {
        val baseProject = ProjectEntity(
            id = "project-1",
            name = "基础水光",
            category = "补水护理",
            description = "基础项目介绍",
            tags = "补水,保湿"
        )
        every { projectRepository.findAll() } returns listOf(baseProject)
        val offerings = listOf(
            InstitutionProjectEntity(
                id = "offering-inherited",
                institutionId = "institution-1",
                projectId = baseProject.id,
                price = BigDecimal("680")
            ),
            InstitutionProjectEntity(
                id = "offering-custom",
                institutionId = "institution-2",
                projectId = baseProject.id,
                name = "院线焕亮升级版",
                description = "针对暗沉提亮肤色",
                price = BigDecimal("880")
            )
        )
        every { institutionProjectRepository.findAll() } returns offerings
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(offerings.map { it.id }) } returns listOf(
            publicBinding("doctor-1", "offering-inherited", baseProject.id, "680"),
            publicBinding("doctor-2", "offering-custom", baseProject.id, "880")
        )
        every { discoverSearchService.extractMatchingFragments("基础水光怎么样") } returns emptySet()
        every { discoverSearchService.extractMatchingFragments("院线焕亮升级版多少钱") } returns emptySet()
        every { discoverSearchService.extractMatchingFragments("我想提亮肤色") } returns setOf("提亮", "肤色")
        every { discoverSearchService.extractMatchingFragments("完全不存在的服务") } returns emptySet()

        assertTrue(service.hasInstitutionProjectMatch("基础水光怎么样"))
        assertTrue(service.hasInstitutionProjectMatch("院线焕亮升级版多少钱"))
        assertTrue(service.hasInstitutionProjectMatch("我想提亮肤色"))
        assertFalse(service.hasInstitutionProjectMatch("完全不存在的服务"))
    }

    @Test
    fun `catalog matching omits unavailable institution offerings`() {
        val baseProject = ProjectEntity(id = "project-1", name = "Base", category = "Skin")
        val offerings = listOf(
            InstitutionProjectEntity(
                id = "offering-available", institutionId = "institution-1", projectId = baseProject.id,
                name = "Available Special", price = BigDecimal("1000")
            ),
            InstitutionProjectEntity(
                id = "offering-unavailable", institutionId = "institution-1", projectId = baseProject.id,
                name = "Unavailable Secret", price = BigDecimal("100")
            )
        )
        every { projectRepository.findAll() } returns listOf(baseProject)
        every { institutionProjectRepository.findAll() } returns offerings
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(offerings.map { it.id }) } returns listOf(
            publicBinding("doctor-1", "offering-available", baseProject.id, "700")
        )
        every { discoverSearchService.extractMatchingFragments(any()) } returns emptySet()

        assertTrue(service.hasInstitutionProjectMatch("Available Special"))
        assertFalse(service.hasInstitutionProjectMatch("Unavailable Secret"))
    }

    @Test
    fun `real report uses eligible doctor minimum for project and offering reference prices`() {
        val project = ProjectEntity(
            id = "project-1",
            name = "水光项目",
            category = "护理",
            referencePrice = BigDecimal("1200")
        )
        val offerings = listOf(
            InstitutionProjectEntity(
                id = "offering-1",
                institutionId = "institution-1",
                projectId = project.id,
                name = "水光基础版",
                price = BigDecimal("1000")
            ),
            InstitutionProjectEntity(
                id = "offering-2",
                institutionId = "institution-1",
                projectId = project.id,
                name = "水光升级版",
                price = BigDecimal("1100")
            )
        )
        val candidates = listOf(
            institutionProjectCandidate("offering-1", "星颜", "水光基础版", project.id, 2, "institution-1"),
            institutionProjectCandidate("offering-2", "星颜", "水光升级版", project.id, 1, "institution-1")
        )
        val institution = InstitutionEntity(id = "institution-1", name = "星颜", city = "上海")
        every { discoverSearchService.citiesMentionedIn(any()) } returns emptyList()
        every { discoverSearchService.explicitlyRequestedEntityTypes(any()) } returns emptySet()
        every { discoverSearchService.extractMatchingFragments(any()) } returns emptySet()
        every { discoverSearchService.search(any<DiscoverSearchRequest>()) } returns DiscoverSearchResult(
            projects = listOf(projectWithOfferings(project, candidates))
        )
        every { institutionRepository.findAll() } returns listOf(institution)
        every { institutionRepository.findAllById(any()) } returns listOf(institution)
        every { projectRepository.findAll() } returns listOf(project)
        every { projectRepository.findAllById(any()) } returns listOf(project)
        every { institutionProjectRepository.findAll() } returns offerings
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(offerings.map { it.id })
        } returns listOf(
            publicBinding("doctor-expensive", "offering-1", project.id, "900"),
            publicBinding("doctor-affordable", "offering-1", project.id, "700"),
            publicBinding("doctor-upgrade", "offering-2", project.id, "800")
        )

        val report = service.report(AgentCatalogReportRequest("水光项目", "SUMMARY"))

        assertEquals("$700", report.items.single { it.type == "PROJECT" }.attributes["项目参考价"])
        assertEquals("$700", report.items.single { it.id == "offering-1" }.attributes["项目参考价"])
        assertEquals("$800", report.items.single { it.id == "offering-2" }.attributes["项目参考价"])
    }

    @Test
    fun `comparison evidence preserves operand order and excludes unrelated ranked items`() {
        val evidence = evidence(
            item("INSTITUTION", "clinic-a", "甲机构", institutionAttributes("北京", "4.8")),
            item("INSTITUTION", "clinic-b", "乙机构", institutionAttributes("上海", "4.7")),
            item("INSTITUTION", "clinic-c", "丙机构", institutionAttributes("广州", "4.9"))
        )
        val request = ComparisonRequest(
            operands = listOf(
                ComparisonOperand(AgentQueryTarget.INSTITUTION, "clinic-b", "乙机构"),
                ComparisonOperand(AgentQueryTarget.INSTITUTION, "clinic-a", "甲机构")
            ),
            targetType = AgentQueryTarget.INSTITUTION,
            dimensions = listOf("RATING")
        )

        val filtered = service.filterComparisonEvidence(evidence, request)

        assertEquals(listOf("clinic-b", "clinic-a"), filtered.report!!.items.map { it.id })
        assertEquals(filtered.report!!.comparisonDimensions, filtered.report!!.items.first().attributes.keys.toList())
        assertEquals(mapOf("INSTITUTION" to listOf("clinic-b", "clinic-a")), filtered.matchedEntityIds)
        assertTrue(filtered.context.indexOf("乙机构") < filtered.context.indexOf("甲机构"))
        assertFalse(filtered.context.contains("丙机构"))
        verify(exactly = 0) { discoverSearchService.search(any<DiscoverSearchRequest>()) }
        verify(exactly = 0) { institutionRepository.findAll() }
        verify(exactly = 0) { doctorRepository.findAll() }
        verify(exactly = 0) { projectRepository.findAll() }
    }

    @Test
    fun `comparison evidence contains only structured allowlisted values`() {
        val source = item(
            type = "INSTITUTION_PROJECT",
            id = "offering-1",
            name = "甲机构 · 水光",
            attributes = linkedMapOf(
                "城市" to "上海",
                "项目分类" to "补水",
                "机构价格" to "$680",
                "项目参考价" to "$880",
                "评分" to "4.8",
                "评价数" to "120",
                "销量" to "80",
                "机构认证" to "已认证",
                "标签" to "补水,保湿",
                "宣传语" to "全网最好",
                "详情摘要" to "保证效果",
                "医生简介" to "不应出现"
            ),
            summary = "自由文本介绍不应进入比较证据"
        )
        val request = ComparisonRequest(
            operands = listOf(ComparisonOperand(AgentQueryTarget.INSTITUTION_PROJECT, "offering-1", "甲机构 · 水光")),
            targetType = AgentQueryTarget.INSTITUTION_PROJECT,
            dimensions = listOf("PRICE", "CREDENTIALS", "RATING")
        )

        val filtered = service.filterComparisonEvidence(evidence(source), request)
        val result = filtered.report!!.items.single()

        assertEquals(
            listOf("城市", "项目分类", "机构价格", "项目参考价", "评分", "评价数", "销量", "机构认证", "标签"),
            result.attributes.keys.toList()
        )
        assertEquals(result.attributes.keys.toList(), filtered.report!!.comparisonDimensions)
        assertEquals("", result.summary)
        assertFalse(filtered.context.contains("全网最好"))
        assertFalse(filtered.context.contains("保证效果"))
        assertFalse(filtered.context.contains("自由文本介绍"))
    }

    @Test
    fun `comparison mode is controlled by routed intent rather than query keywords`() {
        val clinic = InstitutionEntity(id = "clinic-1", name = "星颜", city = "上海")
        stubCatalogSearch(DiscoverSearchResult(institutions = listOf(clinic)), institutions = listOf(clinic))

        val evidence = service.promptEvidence(
            query = "星颜机构信息",
            searchQuery = "星颜机构信息",
            targetQuery = "星颜机构信息",
            queryTarget = AgentQueryTarget.INSTITUTION,
            reportMode = "COMPARISON"
        )

        assertEquals("COMPARISON", evidence.report!!.mode)
        verify(exactly = 1) { discoverSearchService.search(any<DiscoverSearchRequest>()) }
    }

    @Test
    fun `current institution names survive inherited search candidate truncation`() {
        val currentQuery = "本轮甲机构和本轮乙机构对比"
        val expandedSearchQuery = "历史机构1、历史机构2、历史机构3、历史机构4、$currentQuery"
        val inheritedCandidates = (1..4).map { index ->
            InstitutionEntity(
                id = "prior-$index",
                name = "历史机构$index",
                city = "上海",
                rating = BigDecimal("4.${9 - index}")
            )
        }
        val currentCandidates = listOf(
            InstitutionEntity(id = "current-a", name = "本轮甲机构", city = "上海", rating = BigDecimal("4.2")),
            InstitutionEntity(id = "current-b", name = "本轮乙机构", city = "上海", rating = BigDecimal("4.1"))
        )
        val candidates = inheritedCandidates + currentCandidates
        stubCatalogSearch(
            result = DiscoverSearchResult(institutions = candidates),
            institutions = candidates
        )

        val evidence = service.promptEvidence(
            query = currentQuery,
            searchQuery = expandedSearchQuery,
            targetQuery = expandedSearchQuery,
            priorityQuery = currentQuery,
            queryTarget = AgentQueryTarget.INSTITUTION,
            reportMode = "COMPARISON"
        )

        assertEquals(
            listOf("current-a", "current-b", "prior-1", "prior-2"),
            evidence.report!!.items.map { it.id }
        )
        verify(exactly = 1) { discoverSearchService.search(any<DiscoverSearchRequest>()) }
    }

    @Test
    fun `current institution project names survive direct report truncation`() {
        val baseProject = ProjectEntity(id = "base-project", name = "基础项目", category = "护理")
        val inheritedOfferings = (1..9).map { index ->
            institutionProjectCandidate("prior-$index", "共享机构", "历史套餐$index", baseProject.id, 100 - index, "shared-institution")
        }
        val currentOfferings = listOf(
            institutionProjectCandidate("current-a", "共享机构", "当前套餐甲", baseProject.id, 10, "shared-institution"),
            institutionProjectCandidate("current-b", "共享机构", "当前套餐乙", baseProject.id, 9, "shared-institution")
        )
        val candidates = inheritedOfferings + currentOfferings
        val institutions = candidates.map { InstitutionEntity(id = it.institutionId, name = it.institutionName, city = "上海") }
        val offeringEntities = candidates.map { offering ->
            InstitutionProjectEntity(
                id = offering.id,
                institutionId = offering.institutionId,
                projectId = offering.projectId,
                name = offering.name,
                price = offering.price,
                salesCount = offering.salesCount
            )
        }
        every { discoverSearchService.search(any<DiscoverSearchRequest>()) } returns DiscoverSearchResult(
            projects = listOf(projectWithOfferings(baseProject, candidates))
        )
        every { institutionRepository.findAll() } returns institutions
        every { institutionRepository.findAllById(any()) } returns institutions
        every { projectRepository.findAll() } returns listOf(baseProject)
        every { projectRepository.findAllById(any()) } returns listOf(baseProject)
        stubPublicOfferings(offeringEntities)
        every { discoverSearchService.citiesMentionedIn(any()) } returns emptyList()
        every { discoverSearchService.extractMatchingFragments(any()) } returns emptySet()
        every { discoverSearchService.explicitlyRequestedEntityTypes(any()) } returns emptySet<RequestedEntityType>()
        val currentQuery = "共享机构的基础项目：当前套餐甲和当前套餐乙对比"

        val evidence = service.promptEvidence(
            query = currentQuery,
            searchQuery = "${inheritedOfferings.joinToString(" ") { "${it.institutionName} · ${it.name}" }} $currentQuery",
            targetQuery = "${inheritedOfferings.joinToString(" ") { "${it.institutionName} · ${it.name}" }} $currentQuery",
            priorityQuery = currentQuery,
            queryTarget = AgentQueryTarget.INSTITUTION_PROJECT,
            reportMode = "COMPARISON"
        )

        assertEquals(
            listOf("current-a", "current-b", "prior-1", "prior-2", "prior-3", "prior-4", "prior-5", "prior-6"),
            evidence.report!!.items.map { it.id }
        )
        verify(exactly = 1) { discoverSearchService.search(any<DiscoverSearchRequest>()) }
    }

    @Test
    fun `current same institution offerings survive discovered offering deduplication`() {
        val baseProject = ProjectEntity(id = "base-project", name = "基础项目", category = "护理")
        val inheritedOfferings = (1..4).map { index ->
            institutionProjectCandidate("prior-$index", "共享机构", "历史套餐$index", baseProject.id, 100 - index, "shared-institution")
        }
        val currentOfferings = listOf(
            institutionProjectCandidate("current-a", "共享机构", "当前套餐甲", baseProject.id, 10, "shared-institution"),
            institutionProjectCandidate("current-b", "共享机构", "当前套餐乙", baseProject.id, 9, "shared-institution")
        )
        val candidates = inheritedOfferings + currentOfferings
        val institutions = candidates.map { InstitutionEntity(id = it.institutionId, name = it.institutionName, city = "上海") }
        val offeringEntities = candidates.map { offering ->
            InstitutionProjectEntity(
                id = offering.id,
                institutionId = offering.institutionId,
                projectId = offering.projectId,
                name = offering.name,
                price = offering.price,
                salesCount = offering.salesCount
            )
        }
        every { discoverSearchService.search(any<DiscoverSearchRequest>()) } returns DiscoverSearchResult(
            projects = listOf(projectWithOfferings(baseProject, candidates))
        )
        every { institutionRepository.findAll() } returns institutions
        every { institutionRepository.findAllById(any()) } returns institutions
        every { projectRepository.findAll() } returns listOf(baseProject)
        every { projectRepository.findAllById(any()) } returns listOf(baseProject)
        stubPublicOfferings(offeringEntities)
        every { discoverSearchService.citiesMentionedIn(any()) } returns emptyList()
        every { discoverSearchService.extractMatchingFragments(any()) } returns emptySet()
        every { discoverSearchService.explicitlyRequestedEntityTypes(any()) } returns emptySet<RequestedEntityType>()
        val currentQuery = "共享机构的基础项目：当前套餐甲和当前套餐乙对比"

        val evidence = service.promptEvidence(
            query = currentQuery,
            searchQuery = "不匹配的历史上下文",
            targetQuery = "不匹配的历史上下文",
            priorityQuery = currentQuery,
            queryTarget = AgentQueryTarget.INSTITUTION_PROJECT,
            reportMode = "COMPARISON"
        )

        assertEquals(listOf("current-a", "current-b"), evidence.report!!.items.map { it.id })
        verify(exactly = 1) { discoverSearchService.search(any<DiscoverSearchRequest>()) }
    }

    @Test
    fun `auto report with unresolved target retains doctor practice institutions and city behavior`() {
        val shanghaiDoctor = DoctorEntity(id = "doctor-shanghai", name = "李医生")
        val beijingDoctor = DoctorEntity(id = "doctor-beijing", name = "王医生")
        val shanghaiClinic = InstitutionEntity(id = "clinic-shanghai", name = "星颜", city = "上海", isVerified = true)
        val beijingClinic = InstitutionEntity(id = "clinic-beijing", name = "京美", city = "北京", isVerified = true)
        every { doctorInstitutionService.findByDoctorId(shanghaiDoctor.id) } returns listOf(
            relationship("rel-shanghai", shanghaiDoctor.id, shanghaiClinic.id, "APPROVED")
        )
        every { doctorInstitutionService.findByDoctorId(beijingDoctor.id) } returns listOf(
            relationship("rel-beijing", beijingDoctor.id, beijingClinic.id, "APPROVED")
        )
        stubCatalogSearch(
            result = DiscoverSearchResult(doctors = listOf(shanghaiDoctor, beijingDoctor)),
            institutions = listOf(shanghaiClinic, beijingClinic),
            doctors = listOf(shanghaiDoctor, beijingDoctor)
        )
        every { discoverSearchService.citiesMentionedIn("上海星颜") } returns listOf("上海")

        val evidence = service.promptEvidence(
            query = "看看这些",
            searchQuery = "上海星颜",
            targetQuery = "看看这些"
        )

        val item = evidence.report!!.items.single()
        assertEquals("SUMMARY", evidence.report!!.mode)
        assertEquals(shanghaiDoctor.id, item.id)
        assertEquals("星颜（上海）", item.attributes["出诊机构"])
        assertEquals(shanghaiClinic.id, item.institutionId)
        assertTrue(item.canChatWithHuman)
        assertEquals(mapOf("DOCTOR" to listOf(shanghaiDoctor.id)), evidence.matchedEntityIds)
    }

    @Test
    fun `filtering in-memory evidence does not touch any injected collaborator`() {
        val localInstitutionRepository = mockk<InstitutionRepository>(relaxed = true)
        val localDoctorRepository = mockk<DoctorRepository>(relaxed = true)
        val localProjectRepository = mockk<ProjectRepository>(relaxed = true)
        val localInstitutionProjectRepository = mockk<InstitutionProjectRepository>(relaxed = true)
        val localDoctorProjectRepository = mockk<DoctorProjectRepository>(relaxed = true)
        val localDiscoverSearchService = mockk<DiscoverSearchService>(relaxed = true)
        val localDoctorInstitutionService = mockk<DoctorInstitutionService>(relaxed = true)
        val localDetailResolver = mockk<InstitutionProjectDetailResolver>(relaxed = true)
        val localInstitutionConsultantService = mockk<InstitutionConsultantService>(relaxed = true)
        val localAgentProfileService = mockk<AgentProfileService>(relaxed = true)
        val localService = AgentCatalogService(
            localInstitutionRepository,
            localDoctorRepository,
            localProjectRepository,
            localInstitutionProjectRepository,
            localDoctorProjectRepository,
            localDiscoverSearchService,
            localDoctorInstitutionService,
            localDetailResolver,
            localInstitutionConsultantService,
            localAgentProfileService
        )
        val source = evidence(item("PROJECT", "project-1", "水光", mapOf("评分" to "4.8")))

        val filtered = localService.filterComparisonEvidence(
            source,
            ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.PROJECT, "project-1", "水光")),
                targetType = AgentQueryTarget.PROJECT,
                dimensions = listOf("RATING")
            )
        )

        assertEquals(listOf("project-1"), filtered.report!!.items.map { it.id })
        confirmVerified(
            localInstitutionRepository,
            localDoctorRepository,
            localProjectRepository,
            localInstitutionProjectRepository,
            localDoctorProjectRepository,
            localDiscoverSearchService,
            localDoctorInstitutionService,
            localDetailResolver,
            localInstitutionConsultantService,
            localAgentProfileService
        )
    }

    @Test
    fun `doctor comparison keeps one doctor column and summarizes all approved practice institutions`() {
        val doctor = DoctorEntity(
            id = "doctor-1",
            name = "李医生",
            title = "主任医师",
            bio = "不应进入比较证据",
            isVerified = true,
            credentials = "执业医师",
            specialties = "眼部"
        )
        val institutions = listOf(
            InstitutionEntity(id = "clinic-1", name = "仁爱", city = "北京", isVerified = true),
            InstitutionEntity(id = "clinic-2", name = "美莱", city = "广州", isVerified = false),
            InstitutionEntity(id = "clinic-3", name = "华美", city = "深圳", isVerified = true),
            InstitutionEntity(id = "clinic-4", name = "星颜", city = "上海", isVerified = false),
            InstitutionEntity(id = "clinic-pending", name = "待审机构", city = "杭州", isVerified = true),
            InstitutionEntity(id = "clinic-deleted", name = "已删除机构", city = "成都", isVerified = true)
        )
        val deletedAt = LocalDateTime.of(2026, 8, 14, 0, 0)
        every { doctorInstitutionService.findByDoctorId(doctor.id) } returns listOf(
            relationship("rel-1", doctor.id, "clinic-1", "APPROVED"),
            relationship("rel-2", doctor.id, "clinic-2", "APPROVED"),
            relationship("rel-3", doctor.id, "clinic-3", "APPROVED"),
            relationship("rel-4", doctor.id, "clinic-4", "APPROVED"),
            relationship("rel-pending", doctor.id, "clinic-pending", "PENDING"),
            relationship("rel-deleted", doctor.id, "clinic-deleted", "APPROVED", deletedAt)
        )
        stubCatalogSearch(
            result = DiscoverSearchResult(doctors = listOf(doctor)),
            institutions = institutions,
            doctors = listOf(doctor)
        )

        val chinese = service.promptEvidence(
            query = "李医生出诊机构",
            searchQuery = "李医生在上海星颜的出诊机构",
            targetQuery = "李医生出诊机构",
            queryTarget = AgentQueryTarget.DOCTOR,
            reportMode = "COMPARISON"
        ).report!!.items.single()

        assertEquals("doctor-1", chinese.id)
        assertTrue(chinese.attributes.getValue("出诊机构").startsWith("星颜（上海）"))
        assertEquals(3, "（".toRegex().findAll(chinese.attributes.getValue("出诊机构")).count())
        assertTrue(chinese.attributes.getValue("出诊机构").contains("另有 1 家"))
        assertEquals("2/4", chinese.attributes["出诊机构认证"])
        assertEquals("", chinese.summary)

        LocaleContextHolder.setLocale(Locale.ENGLISH)
        val englishReport = service.promptEvidence(
            query = "Doctor Li practice institutions",
            searchQuery = "Doctor Li at Xingyan Shanghai",
            targetQuery = "Doctor Li practice institutions",
            queryTarget = AgentQueryTarget.DOCTOR,
            reportMode = "COMPARISON"
        ).report!!
        val english = englishReport.items.single()
        assertTrue(english.attributes.getValue("Practice institutions").contains("1 more"))
        assertEquals("2/4", english.attributes["Practice institution verification"])
        assertEquals(englishReport.comparisonDimensions, english.attributes.keys.toList())
    }

    @Test
    fun `institution and doctor dimensions never contain price`() {
        val groups = listOf("PRICE", "CREDENTIALS", "RATING")
        val institution = service.filterComparisonEvidence(
            evidence(item("INSTITUTION", "clinic-1", "甲机构", mapOf("城市" to "上海", "机构价格" to "$999"))),
            ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.INSTITUTION, "clinic-1", "甲机构")),
                targetType = AgentQueryTarget.INSTITUTION,
                dimensions = groups
            )
        ).report!!
        val doctor = service.filterComparisonEvidence(
            evidence(item("DOCTOR", "doctor-1", "李医生", mapOf("职称" to "主任医师", "价格" to "$999"))),
            ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.DOCTOR, "doctor-1", "李医生")),
                targetType = AgentQueryTarget.DOCTOR,
                dimensions = groups
            )
        ).report!!

        val priceRows = setOf("价格", "机构价格", "项目参考价", "Price", "Clinic price", "Reference price")
        assertTrue(institution.comparisonDimensions.none { it in priceRows })
        assertTrue(doctor.comparisonDimensions.none { it in priceRows })
        assertFalse(institution.items.single().attributes.containsKey("机构价格"))
        assertFalse(doctor.items.single().attributes.containsKey("价格"))
    }

    @Test
    fun `missing structured fields remain absent`() {
        val filtered = service.filterComparisonEvidence(
            evidence(
                item(
                    "PROJECT",
                    "project-1",
                    "水光",
                    linkedMapOf("项目分类" to "", "项目参考价" to "", "评分" to "4.6", "标签" to "")
                )
            ),
            ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.PROJECT, "project-1", "水光")),
                targetType = AgentQueryTarget.PROJECT,
                dimensions = listOf("PRICE", "RATING")
            )
        ).report!!

        assertEquals(listOf("项目分类", "项目参考价", "评分", "标签"), filtered.comparisonDimensions)
        assertEquals(mapOf("评分" to "4.6"), filtered.items.single().attributes)
        assertNull(filtered.items.single().attributes["项目参考价"])
    }

    private fun evidence(vararg items: AgentCatalogItemResponse): AgentPromptEvidence = AgentPromptEvidence(
        context = "unfiltered context",
        matchedEntityIds = items.toList().groupBy { it.type }.mapValues { (_, values) -> values.map { it.id } },
        report = AgentCatalogReportResponse(
            mode = "SUMMARY",
            title = "原始报告",
            summary = "原始摘要",
            items = items.toList(),
            comparisonDimensions = emptyList(),
            warnings = emptyList()
        )
    )

    private fun item(
        type: String,
        id: String,
        name: String,
        attributes: Map<String, String>,
        summary: String = ""
    ): AgentCatalogItemResponse = AgentCatalogItemResponse(
        type = type,
        id = id,
        name = name,
        subtitle = "",
        summary = summary,
        attributes = attributes
    )

    private fun institutionAttributes(city: String, rating: String): Map<String, String> = linkedMapOf(
        "城市" to city,
        "评分" to rating,
        "评价数" to "10",
        "医生数" to "3",
        "擅长领域" to "皮肤"
    )

    private fun relationship(
        id: String,
        doctorId: String,
        institutionId: String,
        status: String,
        deletedAt: LocalDateTime? = null
    ): DoctorInstitutionEntity = DoctorInstitutionEntity(
        id = id,
        doctorId = doctorId,
        institutionId = institutionId,
        status = status,
        deletedAt = deletedAt
    )

    private fun institutionProjectCandidate(
        id: String,
        institutionName: String,
        name: String,
        projectId: String,
        salesCount: Int,
        institutionId: String = "institution-$id"
    ) = InstitutionProjectItemResponse(
        id = id,
        institutionId = institutionId,
        institutionName = institutionName,
        institutionCity = "上海",
        projectId = projectId,
        name = name,
        category = "护理",
        description = "",
        rating = BigDecimal("4.0"),
        reviewCount = 0,
        tags = "",
        slogan = "",
        detailContent = null,
        price = BigDecimal("100"),
        originalPrice = null,
        coverImage = "",
        images = "",
        salesCount = salesCount,
        isActive = true
    )

    private fun projectWithOfferings(
        project: ProjectEntity,
        offerings: List<InstitutionProjectItemResponse>
    ) = ProjectWithInstitutionsResponse(
        id = project.id,
        name = project.name,
        category = project.category,
        description = project.description,
        tags = project.tags,
        categoryTags = project.categoryTags,
        coverImage = project.coverImage,
        images = project.images,
        referencePrice = project.referencePrice,
        slogan = project.slogan,
        detailContent = project.detailContent,
        salesCount = project.salesCount,
        rating = project.rating,
        reviewCount = project.reviewCount,
        institutionProjects = offerings
    )

    private fun institution(
        id: String,
        name: String,
        city: String,
        rating: String,
        isVerified: Boolean = true
    ) = InstitutionEntity(
        id = id,
        name = name,
        city = city,
        rating = BigDecimal(rating),
        isVerified = isVerified
    )

    private fun stubConsultableInstitutions(
        institutions: List<InstitutionEntity>,
        consultableIds: Set<String>,
        profileCity: String = ""
    ) {
        every { institutionRepository.findAll() } returns institutions
        every { institutionRepository.countSoftDeletedNamesMentionedInQuery(any()) } returns 0
        every { institutionConsultantService.listConsultableInstitutionIds() } returns consultableIds
        every { discoverSearchService.citiesMentionedIn(any()) } returns emptyList()
        every { agentProfileService.get(any()) } returns profile(profileCity)
    }

    private fun profile(city: String) = AgentProfileResponse(
        id = null,
        city = city,
        goals = emptyList(),
        budgetMin = null,
        budgetMax = null,
        acceptableDowntimeDays = null,
        painTolerance = "",
        preferences = emptyList(),
        excludedProjects = emptyList(),
        consentVersion = "",
        confirmedAt = null,
        completenessScore = 0,
        missingFields = emptyList()
    )

    private fun stubCatalogSearch(
        result: DiscoverSearchResult,
        institutions: List<InstitutionEntity> = emptyList(),
        doctors: List<DoctorEntity> = emptyList()
    ) {
        every { institutionRepository.findAll() } returns institutions
        every { doctorRepository.findAll() } returns doctors
        every { projectRepository.findAll() } returns emptyList()
        every { institutionProjectRepository.findAll() } returns emptyList()
        every { discoverSearchService.citiesMentionedIn(any()) } returns emptyList()
        every { discoverSearchService.extractMatchingFragments(any()) } returns emptySet()
        every { discoverSearchService.explicitlyRequestedEntityTypes(any()) } returns emptySet<RequestedEntityType>()
        every { discoverSearchService.search(any<DiscoverSearchRequest>()) } returns result
    }
}
