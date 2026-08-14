package com.joysong.server.agent.service

import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.discover.service.DiscoverSearchRequest
import com.joysong.server.discover.service.DiscoverSearchResult
import com.joysong.server.discover.repository.DoctorProjectRepository
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
    private val service = AgentCatalogService(
        institutionRepository,
        doctorRepository,
        projectRepository,
        institutionProjectRepository,
        doctorProjectRepository,
        discoverSearchService,
        doctorInstitutionService,
        InstitutionProjectDetailResolver()
    )

    @BeforeEach
    fun useChineseLocale() {
        LocaleContextHolder.setLocale(Locale.CHINESE)
    }

    @AfterEach
    fun resetLocale() {
        LocaleContextHolder.resetLocaleContext()
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
        every { institutionProjectRepository.findAll() } returns listOf(
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
        val localService = AgentCatalogService(
            localInstitutionRepository,
            localDoctorRepository,
            localProjectRepository,
            localInstitutionProjectRepository,
            localDoctorProjectRepository,
            localDiscoverSearchService,
            localDoctorInstitutionService,
            localDetailResolver
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
            localDetailResolver
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
