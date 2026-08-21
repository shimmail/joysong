package com.joysong.server.discover.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.diary.repository.DiaryRepository
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
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
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
    private val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
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
        mockk<DoctorProjectRepository>(),
        mockk<DiscoverService>(),
        mockk<DiscoverSearchService>(),
        configRepository,
        InstitutionProjectDetailResolver(),
        consultants,
        pricing
    )

    @Test
    fun `travel ground service quote wraps configured price in API response`() {
        every {
            configRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1")
        } returns DoctorInstitutionProjectConfigEntity(
            doctorId = "doctor-1",
            institutionProjectId = "ip-1",
            medicalListPrice = BigDecimal("1000.00")
        )
        val response = controller.getTravelGroundServiceQuote("doctor-1", "ip-1")
        val quote = requireNotNull(response.data)

        assertEquals(200, response.code)
        assertEquals("USD", quote.currency)
        assertEquals(100_000, quote.medicalListPriceMinor)
        assertEquals(4_000, quote.platformServiceRateBps)
        assertEquals(40_000, quote.travelGroundServiceFeeMinor)
    }

    @Test
    fun `travel ground service quote rejects missing or zero doctor configuration`() {
        every { configRepository.findByDoctorIdAndInstitutionProjectId("missing", "ip-1") } returns null
        every {
            configRepository.findByDoctorIdAndInstitutionProjectId("zero", "ip-1")
        } returns DoctorInstitutionProjectConfigEntity(
            doctorId = "zero",
            institutionProjectId = "ip-1",
            medicalListPrice = BigDecimal.ZERO
        )

        assertEquals(
            "MEDICAL_LIST_PRICE_NOT_CONFIGURED",
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
