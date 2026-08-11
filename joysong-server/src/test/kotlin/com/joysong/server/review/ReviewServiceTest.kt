package com.joysong.server.review

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.review.entity.ReviewEntity
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.review.service.ReviewService
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.service.SettlementService
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.justRun
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

class ReviewServiceTest {

    @MockK private lateinit var reviewRepository: ReviewRepository
    @MockK private lateinit var orderRepository: OrderRepository
    @MockK private lateinit var settlementService: SettlementService
    @MockK private lateinit var orderStatusLogService: OrderStatusLogService
    @MockK private lateinit var doctorRepository: DoctorRepository
    @MockK private lateinit var institutionRepository: InstitutionRepository
    @MockK private lateinit var institutionProjectRepository: InstitutionProjectRepository

    private lateinit var reviewService: ReviewService

    private val order = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "project",
        price = BigDecimal("100.00"),
        status = "COMPLETED",
        projectId = "project-1",
        institutionId = "institution-1",
        doctorId = "doctor-1",
        institutionProjectId = "institution-project-1"
    )

    private val institution = InstitutionEntity(
        id = "institution-1",
        name = "institution",
        rating = BigDecimal("4.8"),
        reviewCount = 99,
        caseCount = 37
    )

    private val doctor = DoctorEntity(
        id = "doctor-1",
        name = "doctor",
        rating = BigDecimal("4.7"),
        reviewCount = 88,
        caseCount = 29
    )

    private val institutionProject = InstitutionProjectEntity(
        id = "institution-project-1",
        institutionId = "institution-1",
        projectId = "project-1",
        rating = BigDecimal("4.6"),
        reviewCount = 77
    )

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        reviewService = ReviewService(
            reviewRepository,
            orderRepository,
            settlementService,
            orderStatusLogService,
            doctorRepository,
            institutionRepository,
            institutionProjectRepository
        )

        every { reviewRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { institutionRepository.save(any()) } answers { firstArg() }
        every { doctorRepository.save(any()) } answers { firstArg() }
        every { institutionProjectRepository.save(any()) } answers { firstArg() }
        justRun { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `submit review recalculates institution doctor and institution project stats`() {
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        every {
            reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", order.institutionId)
        } returns listOf(review("review-1", 5), review("review-2", 4))
        every { reviewRepository.findByDoctorIdAndTargetType(order.doctorId, "INSTITUTION") } returns
            listOf(review("review-1", 5), review("review-2", 3))
        every { reviewRepository.findByInstitutionProjectId(order.institutionProjectId) } returns
            listOf(review("review-1", 5), review("review-2", 2))
        stubAggregateTargets()
        every { settlementService.saveSettlement(order.id, any()) } returns io.mockk.mockk<SettlementEntity>()

        reviewService.submitReview(order.id, order.userId, 5, "great", "", "")

        val institutionSaved = slot<InstitutionEntity>()
        val doctorSaved = slot<DoctorEntity>()
        val projectSaved = slot<InstitutionProjectEntity>()
        verify { institutionRepository.save(capture(institutionSaved)) }
        verify { doctorRepository.save(capture(doctorSaved)) }
        verify { institutionProjectRepository.save(capture(projectSaved)) }
        assertEquals(2, institutionSaved.captured.reviewCount)
        assertEquals(BigDecimal("4.5"), institutionSaved.captured.rating)
        assertEquals(37, institutionSaved.captured.caseCount)
        assertEquals(2, doctorSaved.captured.reviewCount)
        assertEquals(BigDecimal("4.0"), doctorSaved.captured.rating)
        assertEquals(29, doctorSaved.captured.caseCount)
        assertEquals(2, projectSaved.captured.reviewCount)
        assertEquals(BigDecimal("3.5"), projectSaved.captured.rating)
    }

    @Test
    fun `automatic review creates editable canonical review and recalculates stats`() {
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        every { reviewRepository.findByOrderIdAndTargetType(order.id, "INSTITUTION") } returns Optional.empty()
        every {
            reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", order.institutionId)
        } returns listOf(review("automatic-review", 5))
        every { reviewRepository.findByDoctorIdAndTargetType(order.doctorId, "INSTITUTION") } returns
            listOf(review("automatic-review", 5))
        every { reviewRepository.findByInstitutionProjectId(order.institutionProjectId) } returns
            listOf(review("automatic-review", 5))
        stubAggregateTargets()
        every { settlementService.saveSettlement(order.id, any()) } returns io.mockk.mockk<SettlementEntity>()

        val result = reviewService.submitAutomaticReview(order.id)

        assertEquals(5, result?.rating)
        assertEquals("INSTITUTION", result?.targetType)
        verify { orderRepository.save(match { it.hasReview && it.status == "PENDING_SETTLEMENT" }) }
        verify { institutionRepository.save(match { it.reviewCount == 1 && it.rating == BigDecimal("5.0") }) }
        verify { doctorRepository.save(match { it.reviewCount == 1 && it.rating == BigDecimal("5.0") }) }
        verify { institutionProjectRepository.save(match { it.reviewCount == 1 && it.rating == BigDecimal("5.0") }) }
        verify { settlementService.saveSettlement(order.id, any()) }
    }

    @Test
    fun `update review recalculates all aggregate targets using updated values`() {
        val existing = review("review-1", 2)
        every { reviewRepository.findById(existing.id) } returns Optional.of(existing)
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        every {
            reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", order.institutionId)
        } returns listOf(existing.copy(rating = 5), review("review-2", 4))
        every { reviewRepository.findByDoctorIdAndTargetType(order.doctorId, "INSTITUTION") } returns
            listOf(existing.copy(rating = 5), review("review-2", 4))
        every { reviewRepository.findByInstitutionProjectId(order.institutionProjectId) } returns
            listOf(existing.copy(rating = 5), review("review-2", 4))
        stubAggregateTargets()

        val result = reviewService.updateReview(existing.id, order.userId, 5, "updated", "tag", "image")

        assertEquals(5, result.rating)
        assertEquals("updated", result.content)
        verify { institutionRepository.save(match { it.reviewCount == 2 && it.rating == BigDecimal("4.5") }) }
        verify { doctorRepository.save(match { it.reviewCount == 2 && it.rating == BigDecimal("4.5") }) }
        verify { institutionProjectRepository.save(match { it.reviewCount == 2 && it.rating == BigDecimal("4.5") }) }
    }

    @Test
    fun `delete review flushes soft delete then resets all aggregate targets`() {
        val existing = review("review-1", 5)
        every { reviewRepository.findById(existing.id) } returns Optional.of(existing)
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        justRun { reviewRepository.deleteById(existing.id) }
        justRun { reviewRepository.flush() }
        every {
            reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", order.institutionId)
        } returns emptyList()
        every {
            reviewRepository.findByDoctorIdAndTargetType(order.doctorId, "INSTITUTION")
        } returns emptyList()
        every { reviewRepository.findByInstitutionProjectId(order.institutionProjectId) } returns emptyList()
        stubAggregateTargets()

        reviewService.deleteUserReview(existing.id, order.userId)

        verifyOrder {
            reviewRepository.deleteById(existing.id)
            reviewRepository.flush()
            reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", order.institutionId)
        }
        verify { orderRepository.save(match { !it.hasReview }) }
        verify { institutionRepository.save(match { it.reviewCount == 0 && it.rating == BigDecimal.ZERO && it.caseCount == 37 }) }
        verify { doctorRepository.save(match { it.reviewCount == 0 && it.rating == BigDecimal.ZERO && it.caseCount == 29 }) }
        verify { institutionProjectRepository.save(match { it.reviewCount == 0 && it.rating == BigDecimal.ZERO }) }
    }

    private fun stubAggregateTargets() {
        every { institutionRepository.findById(order.institutionId) } returns Optional.of(institution)
        every { doctorRepository.findById(order.doctorId) } returns Optional.of(doctor)
        every { institutionProjectRepository.findById(order.institutionProjectId) } returns
            Optional.of(institutionProject)
    }

    private fun review(id: String, rating: Int) = ReviewEntity(
        id = id,
        orderId = order.id,
        userId = order.userId,
        doctorId = order.doctorId,
        rating = rating,
        content = "content",
        targetType = "INSTITUTION",
        targetId = order.institutionId
    )
}
