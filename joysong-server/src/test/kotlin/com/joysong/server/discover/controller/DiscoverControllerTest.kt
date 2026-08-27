package com.joysong.server.discover.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.repository.PublicDoctorProjectView
import com.joysong.server.discover.dto.FilterOptionsResponse
import com.joysong.server.discover.service.DiscoverDetailService
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.discover.service.DiscoverService
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.InstitutionConsultant
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.TravelGroundServiceQuote
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.project.entity.ProjectEntity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DiscoverControllerTest {
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val institutionRepository = mockk<InstitutionRepository>()
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>()
    private val orderService = mockk<OrderService>()
    private val consultants = mockk<InstitutionConsultantService>()
    private val controller = DiscoverController(
        projectRepository,
        mockk<DiaryRepository>(),
        mockk<DoctorRepository>(),
        institutionRepository,
        mockk<ArticleRepository>(),
        mockk<DiscoverDetailService>(),
        institutionProjectRepository,
        doctorProjectRepository,
        mockk<DiscoverService>(),
        mockk<DiscoverSearchService>(),
        InstitutionProjectDetailResolver(),
        consultants,
        orderService
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

    @Test
    fun `filter options omit categories tags and cities from unavailable offerings`() {
        val eligibleProject = ProjectEntity(
            id = "project-eligible", name = "Eligible", category = "Eligible Category", tags = "eligible-tag"
        )
        val unavailableProject = ProjectEntity(
            id = "project-unavailable", name = "Unavailable", category = "Secret Category", tags = "secret-tag"
        )
        val eligibleOffering = InstitutionProjectEntity(
            id = "ip-eligible", institutionId = "institution-eligible", projectId = "project-eligible",
            price = BigDecimal("1000")
        )
        val unavailableOffering = InstitutionProjectEntity(
            id = "ip-unavailable", institutionId = "institution-unavailable", projectId = "project-unavailable",
            price = BigDecimal("100")
        )
        every { projectRepository.findAll() } returns listOf(eligibleProject, unavailableProject)
        every { institutionProjectRepository.findAll() } returns listOf(eligibleOffering, unavailableOffering)
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-eligible", "ip-unavailable"))
        } returns listOf(publicBinding("doctor-1", "ip-eligible", "project-eligible", "700"))
        every { institutionRepository.findAll() } returns listOf(
            InstitutionEntity("institution-eligible", "Eligible Institution", city = "Shanghai"),
            InstitutionEntity("institution-unavailable", "Unavailable Institution", city = "Secret City")
        )

        val result = controller.getFilterOptions().data as FilterOptionsResponse

        assertEquals(listOf("Eligible Category"), result.categories)
        assertEquals(listOf("eligible-tag"), result.tags)
        assertEquals(listOf("Shanghai"), result.cities)
    }

    @Test
    fun `institution directory omits institutions without available offerings`() {
        val availableOffering = InstitutionProjectEntity(
            id = "ip-available", institutionId = "institution-available", projectId = "project-1",
            price = BigDecimal("1000")
        )
        val unavailableOffering = InstitutionProjectEntity(
            id = "ip-unavailable", institutionId = "institution-unavailable", projectId = "project-2",
            price = BigDecimal("100")
        )
        every { institutionProjectRepository.findAll() } returns listOf(availableOffering, unavailableOffering)
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-available", "ip-unavailable"))
        } returns listOf(publicBinding("doctor-1", "ip-available", "project-1", "700"))
        every { institutionRepository.findAll() } returns listOf(
            InstitutionEntity("institution-unavailable", "Unavailable"),
            InstitutionEntity("institution-available", "Available")
        )

        val result = controller.getInstitutions(query = "", offset = 0, limit = 50).body!!.data!!

        assertEquals(listOf("institution-available"), result.map { it.id })
    }

    @Test
    fun `quote uses each selected doctor price without a medical config`() {
        every { orderService.quoteTravelGroundService("doctor-a", "ip-1") } returns
            TravelGroundServiceQuote("USD", 399_900, 4000, 159_960, "travel-ground-service-rate:0.400000")
        every { orderService.quoteTravelGroundService("doctor-b", "ip-1") } returns
            TravelGroundServiceQuote("USD", 429_900, 4000, 171_960, "travel-ground-service-rate:0.400000")

        assertEquals(159_960L, controller.getTravelGroundServiceQuote("doctor-a", "ip-1").data!!.travelGroundServiceFeeMinor)
        assertEquals(171_960L, controller.getTravelGroundServiceQuote("doctor-b", "ip-1").data!!.travelGroundServiceFeeMinor)
    }

    @Test
    fun `travel ground service quote rejects missing or zero doctor project price`() {
        every { orderService.quoteTravelGroundService("missing", "ip-1") } throws
            IllegalArgumentException("DOCTOR_PROJECT_NOT_CONFIGURED")
        every { orderService.quoteTravelGroundService("zero", "ip-1") } throws
            IllegalArgumentException("MEDICAL_LIST_PRICE_NOT_POSITIVE")

        assertEquals(
            "DOCTOR_PROJECT_NOT_CONFIGURED",
            assertThrows(IllegalArgumentException::class.java) {
                controller.getTravelGroundServiceQuote("missing", "ip-1")
            }.message
        )
        assertEquals(
            "MEDICAL_LIST_PRICE_NOT_POSITIVE",
            assertThrows(IllegalArgumentException::class.java) {
                controller.getTravelGroundServiceQuote("zero", "ip-1")
            }.message
        )
    }

    @Test
    fun `consultant picker returns only the approved public profile`() {
        every { consultants.listApprovedConsultants("inst-1") } returns listOf(
            InstitutionConsultant(
                id = "consultant-1",
                name = "测试咨询师",
                avatar = "consultant.png",
                institutionId = "inst-1",
                institutionName = "美丽机构"
            )
        )

        val response = controller.getInstitutionConsultants("inst-1")
        val fields = jacksonObjectMapper().readTree(
            jacksonObjectMapper().writeValueAsString(response.data)
        )[0]

        assertEquals(setOf("id", "name", "avatar", "institutionId", "institutionName"), fields.fieldNames().asSequence().toSet())
        assertFalse(fields.has("phone"))
        assertFalse(fields.has("email"))
        assertFalse(fields.has("wechat"))
    }
}
