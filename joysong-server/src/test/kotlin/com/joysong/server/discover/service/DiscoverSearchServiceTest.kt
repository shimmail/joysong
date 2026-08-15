package com.joysong.server.discover.service

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DiscoverSearchServiceTest {
    private val institutionRepository = mockk<InstitutionRepository>()
    private val service = DiscoverSearchService(
        projectRepository = mockk<ProjectRepository>(),
        institutionRepository = institutionRepository,
        institutionProjectRepository = mockk<InstitutionProjectRepository>(),
        doctorRepository = mockk<DoctorRepository>(),
        keywordExtractor = DiscoverKeywordExtractor(),
        doctorInstitutionService = mockk<DoctorInstitutionService>(),
        institutionProjectDetailResolver = InstitutionProjectDetailResolver()
    )

    @Test
    fun `matching fragments keep custom terms and remove conversational noise`() {
        every { institutionRepository.findAll() } returns emptyList()

        val fragments = service.extractMatchingFragments("我想了解提亮肤色项目怎么样")

        assertTrue("提亮" in fragments)
        assertTrue("肤色" in fragments)
        assertFalse("我想" in fragments)
        assertFalse("怎么" in fragments)
    }

    @Test
    fun `named institution phrase detection excludes generic institution requests`() {
        assertTrue(service.hasNamedInstitutionPhrase("星颜医疗美容医院"))
        assertTrue(service.hasNamedInstitutionPhrase("Aurora clinic"))
        assertFalse(service.hasNamedInstitutionPhrase("推荐医美机构"))
        assertFalse(service.hasNamedInstitutionPhrase("find a clinic"))
        assertFalse(service.hasNamedInstitutionPhrase("我想找真人咨询"))
    }

    @Test
    fun `priority query keeps current lower rated entities before search limit`() {
        val projectRepository = mockk<ProjectRepository>(relaxed = true)
        val institutionRepository = mockk<InstitutionRepository>(relaxed = true)
        val institutionProjectRepository = mockk<InstitutionProjectRepository>(relaxed = true)
        val doctorRepository = mockk<DoctorRepository>(relaxed = true)
        val doctors = (1..4).map { index ->
            DoctorEntity(id = "prior-doctor-$index", name = "历史医生$index", rating = BigDecimal("4.${9 - index}"))
        } + listOf(
            DoctorEntity(id = "current-doctor-a", name = "当前医生甲", rating = BigDecimal("4.2")),
            DoctorEntity(id = "current-doctor-b", name = "当前医生乙", rating = BigDecimal("4.1"))
        )
        val institutions = (1..4).map { index ->
            InstitutionEntity(id = "prior-institution-$index", name = "历史机构$index", city = "上海", rating = BigDecimal("4.${9 - index}"))
        } + listOf(
            InstitutionEntity(id = "current-institution-a", name = "当前机构甲", city = "上海", rating = BigDecimal("4.2")),
            InstitutionEntity(id = "current-institution-b", name = "当前机构乙", city = "上海", rating = BigDecimal("4.1"))
        )
        val projects = (1..4).map { index ->
            ProjectEntity(id = "prior-project-$index", name = "历史项目$index", category = "护理", rating = BigDecimal("4.${9 - index}"))
        } + listOf(
            ProjectEntity(id = "current-project-a", name = "当前项目甲", category = "护理", rating = BigDecimal("4.2")),
            ProjectEntity(id = "current-project-b", name = "当前项目乙", category = "护理", rating = BigDecimal("4.1"))
        )
        val offerings = projects.mapIndexed { index, project ->
            InstitutionProjectEntity(
                id = "offering-${index + 1}",
                institutionId = institutions[index].id,
                projectId = project.id,
                price = BigDecimal("100"),
                salesCount = 100 - index
            )
        }
        every { projectRepository.findAll() } returns projects
        every { institutionRepository.findAll() } returns institutions
        every { institutionProjectRepository.findAll() } returns offerings
        every { doctorRepository.findAll() } returns doctors
        val localService = DiscoverSearchService(
            projectRepository = projectRepository,
            institutionRepository = institutionRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorRepository = doctorRepository,
            keywordExtractor = DiscoverKeywordExtractor(),
            doctorInstitutionService = mockk(relaxed = true),
            institutionProjectDetailResolver = InstitutionProjectDetailResolver()
        )
        val currentQuery = "当前机构甲 当前机构乙 当前医生甲 当前医生乙 当前项目甲 当前项目乙"
        val result = localService.search(
            DiscoverSearchRequest(
                query = "历史机构1 历史机构2 历史机构3 历史机构4 历史医生1 历史医生2 历史医生3 历史医生4 历史项目1 历史项目2 历史项目3 历史项目4 $currentQuery",
                priorityQuery = currentQuery,
                limit = 4
            )
        )

        assertEquals(listOf("current-institution-a", "current-institution-b", "prior-institution-1", "prior-institution-2"), result.institutions.map { it.id })
        assertEquals(listOf("current-doctor-a", "current-doctor-b", "prior-doctor-1", "prior-doctor-2"), result.doctors.map { it.id })
        assertEquals(listOf("current-project-a", "current-project-b", "prior-project-1", "prior-project-2"), result.projects.map { it.id })
    }

    @Test
    fun `specific offering names outrank same institution siblings before project limit`() {
        val projectRepository = mockk<ProjectRepository>(relaxed = true)
        val institutionRepository = mockk<InstitutionRepository>(relaxed = true)
        val institutionProjectRepository = mockk<InstitutionProjectRepository>(relaxed = true)
        val doctorRepository = mockk<DoctorRepository>(relaxed = true)
        val sharedInstitution = InstitutionEntity(id = "shared", name = "共享机构", city = "上海")
        val projects = (1..4).map { index ->
            ProjectEntity(id = "prior-$index", name = "历史项目$index", category = "护理", rating = BigDecimal("4.${9 - index}"))
        } + listOf(
            ProjectEntity(id = "current-a", name = "当前项目甲", category = "护理", rating = BigDecimal("4.2")),
            ProjectEntity(id = "current-b", name = "当前项目乙", category = "护理", rating = BigDecimal("4.1"))
        )
        val offerings = projects.mapIndexed { index, project ->
            InstitutionProjectEntity(
                id = "offering-${index + 1}",
                institutionId = sharedInstitution.id,
                projectId = project.id,
                name = if (project.id.startsWith("current")) project.name.replace("项目", "套餐") else "历史套餐${index + 1}",
                price = BigDecimal("100"),
                salesCount = 100 - index
            )
        }
        every { projectRepository.findAll() } returns projects
        every { institutionRepository.findAll() } returns listOf(sharedInstitution)
        every { institutionProjectRepository.findAll() } returns offerings
        every { doctorRepository.findAll() } returns emptyList()
        val localService = DiscoverSearchService(
            projectRepository = projectRepository,
            institutionRepository = institutionRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorRepository = doctorRepository,
            keywordExtractor = DiscoverKeywordExtractor(),
            doctorInstitutionService = mockk(relaxed = true),
            institutionProjectDetailResolver = InstitutionProjectDetailResolver()
        )
        val currentQuery = "共享机构 · 当前套餐甲和共享机构 · 当前套餐乙"

        val result = localService.search(
            DiscoverSearchRequest(
                query = "${projects.joinToString(" ") { it.name }} $currentQuery",
                priorityQuery = currentQuery,
                scopes = setOf(DiscoverSearchScope.PROJECT),
                limit = 4
            )
        )

        assertEquals(listOf("current-a", "current-b", "prior-1", "prior-2"), result.projects.map { it.id })
    }
}
