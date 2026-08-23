package com.joysong.server.discover.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.service.DiscoverDetailService
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.discover.service.DiscoverService
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.InstitutionConsultant
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DiscoverControllerTest {
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val pricing = TravelGroundServicePricing(
        OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") })
    )
    private val consultants = mockk<InstitutionConsultantService>()
    private val controller = DiscoverController(
        mockk<ProjectRepository>(),
        mockk<DiaryRepository>(),
        mockk<DoctorRepository>(),
        mockk<InstitutionRepository>(),
        mockk<ArticleRepository>(),
        mockk<DiscoverDetailService>(),
        mockk<InstitutionProjectRepository>(),
        doctorProjectRepository,
        mockk<DiscoverService>(),
        mockk<DiscoverSearchService>(),
        InstitutionProjectDetailResolver(),
        consultants,
        pricing
    )

    @Test
    fun `quote uses each selected doctor price without a medical config`() {
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-a", "ip-1") } returns
            DoctorProjectEntity("doctor-a", "project-1", "ip-1", BigDecimal("3999.00"))
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-b", "ip-1") } returns
            DoctorProjectEntity("doctor-b", "project-1", "ip-1", BigDecimal("4299.00"))

        assertEquals(159_960L, controller.getTravelGroundServiceQuote("doctor-a", "ip-1").data!!.travelGroundServiceFeeMinor)
        assertEquals(171_960L, controller.getTravelGroundServiceQuote("doctor-b", "ip-1").data!!.travelGroundServiceFeeMinor)
    }

    @Test
    fun `travel ground service quote rejects missing or zero doctor project price`() {
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("missing", "ip-1") } returns null
        every {
            doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("zero", "ip-1")
        } returns DoctorProjectEntity(
            doctorId = "zero",
            projectId = "project-1",
            institutionProjectId = "ip-1",
            price = BigDecimal.ZERO
        )

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
