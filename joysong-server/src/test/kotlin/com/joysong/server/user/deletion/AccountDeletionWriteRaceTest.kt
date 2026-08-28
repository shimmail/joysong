package com.joysong.server.user.deletion

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.auth.service.InvalidRefreshTokenException
import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.comment.repository.CommentRepository
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.identity.service.AdminIdentityService
import com.joysong.server.identity.service.ConsultantInstitutionRelationshipOperations
import com.joysong.server.identity.service.DoctorInstitutionRelationshipOperations
import com.joysong.server.identity.service.IdentityApplicationService
import com.joysong.server.identity.service.SubmitIdentityApplicationRequest
import com.joysong.server.like.entity.dto.LikeRequest
import com.joysong.server.like.repository.LikeRepository
import com.joysong.server.like.service.LikeService
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.report.entity.dto.ReportRequest
import com.joysong.server.report.repository.ReportRepository
import com.joysong.server.report.service.ReportService
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.review.service.ReviewService
import com.joysong.server.user.service.AccountLifecycleGuard
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.JdbcTemplate

class AccountDeletionWriteRaceTest {
    @Test
    fun `social write takes the shared lifecycle row lock before touching content`() {
        val likeRepository = mockk<LikeRepository>(relaxed = true)
        val diaryRepository = mockk<DiaryRepository>(relaxed = true)
        val commentRepository = mockk<CommentRepository>(relaxed = true)
        val lifecycleGuard = mockk<AccountLifecycleGuard>()
        every { lifecycleGuard.requireActiveForWrite("user-1") } throws IllegalStateException("账号不可用")
        val service = LikeService(likeRepository, diaryRepository, commentRepository, lifecycleGuard)

        assertThrows(IllegalStateException::class.java) {
            service.addLike("user-1", LikeRequest("diary", "diary-1"))
        }

        verify(exactly = 1) { lifecycleGuard.requireActiveForWrite("user-1") }
        verify(exactly = 0) { diaryRepository.existsById(any()) }
        verify(exactly = 0) { likeRepository.save(any()) }
        assertNotNull(
            LikeService::class.java
                .getMethod("addLike", String::class.java, LikeRequest::class.java)
                .getAnnotation(Transactional::class.java),
        )
    }

    @Test
    fun `identity submission takes the shared lifecycle row lock before identity data access`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val lifecycleGuard = unavailableGuard()
        val service = construct<IdentityApplicationService>(jdbcTemplate, ObjectMapper(), lifecycleGuard)

        assertThrows(IllegalStateException::class.java) {
            service.submit("user-1", SubmitIdentityApplicationRequest(roleCode = "DOCTOR"))
        }

        verify(exactly = 1) { lifecycleGuard.requireActiveForWrite("user-1") }
        verify { jdbcTemplate wasNot Called }
    }

    @Test
    fun `consultant binding locks the active target account before relationship data access`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val lifecycleGuard = unavailableGuard()
        val consultantRelationships = mockk<ConsultantInstitutionRelationshipOperations>(relaxed = true)
        val service = adminIdentityService(jdbcTemplate, lifecycleGuard, consultantRelationships)

        assertThrows(IllegalStateException::class.java) {
            service.bindConsultant("user-1", "institution-1", "admin-1")
        }

        verify(exactly = 1) { lifecycleGuard.requireActiveForWrite("user-1") }
        verify(exactly = 0) { consultantRelationships.lockPair(any(), any()) }
        verify { jdbcTemplate wasNot Called }
    }

    @Test
    fun `membership creation locks the active target account before role data access`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val lifecycleGuard = unavailableGuard()
        val consultantRelationships = mockk<ConsultantInstitutionRelationshipOperations>(relaxed = true)
        val service = adminIdentityService(jdbcTemplate, lifecycleGuard, consultantRelationships)

        assertThrows(IllegalStateException::class.java) {
            service.createMembership("user-1", "institution-1", "INSTITUTION_CUSTOMER_SERVICE", "admin-1")
        }

        verify(exactly = 1) { lifecycleGuard.requireActiveForWrite("user-1") }
        verify { jdbcTemplate wasNot Called }
    }

    @Test
    fun `report submission takes the shared lifecycle row lock before report data access`() {
        val reportRepository = mockk<ReportRepository>(relaxed = true)
        val lifecycleGuard = unavailableGuard()
        val service = construct<ReportService>(
            reportRepository,
            mockk<DiaryRepository>(relaxed = true),
            mockk<ReviewRepository>(relaxed = true),
            mockk<CommentRepository>(relaxed = true),
            mockk<UserRepository>(relaxed = true),
            ObjectMapper(),
            mockk<ReviewService>(relaxed = true),
            lifecycleGuard,
        )

        assertThrows(IllegalStateException::class.java) {
            service.submitReport(
                "user-1",
                ReportRequest(targetType = "user", targetId = "target-1", reason = "spam", description = "spam"),
            )
        }

        verify(exactly = 1) { lifecycleGuard.requireActiveForWrite("user-1") }
        verify { reportRepository wasNot Called }
        assertNotNull(
            ReportService::class.java
                .getMethod("submitReport", String::class.java, ReportRequest::class.java)
                .getAnnotation(Transactional::class.java),
        )
    }

    @Test
    fun `refresh token issue translates inactive account race to authentication failure before insert`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val lifecycleGuard = unavailableGuard()
        val tokenProvider = JwtTokenProvider(
            secret = "test-secret-key-that-is-at-least-32-characters-long",
            expiration = 60_000,
            adminExpiration = 30_000,
        )
        val service = construct<RefreshTokenService>(jdbcTemplate, tokenProvider, 60_000L, lifecycleGuard)

        assertThrows(InvalidRefreshTokenException::class.java) {
            service.issue("user-1", "13800000000", "USER")
        }

        verify(exactly = 1) { lifecycleGuard.requireActiveForWrite("user-1") }
        verify { jdbcTemplate wasNot Called }
    }

    private fun unavailableGuard(): AccountLifecycleGuard = mockk<AccountLifecycleGuard>().also { guard ->
        every { guard.requireActiveForWrite("user-1") } throws IllegalStateException("账号不可用")
    }

    private fun adminIdentityService(
        jdbcTemplate: JdbcTemplate,
        lifecycleGuard: AccountLifecycleGuard,
        consultantRelationships: ConsultantInstitutionRelationshipOperations,
    ): AdminIdentityService = construct(
        jdbcTemplate,
        ObjectMapper(),
        mockk<DoctorInstitutionRelationshipOperations>(relaxed = true),
        mockk<WalletRepository>(relaxed = true),
        consultantRelationships,
        mockk<BusinessNotificationService>(relaxed = true),
        lifecycleGuard,
    )

    private inline fun <reified T : Any> construct(vararg dependencies: Any): T {
        val constructor = T::class.java.declaredConstructors.single()
        val arguments = constructor.parameterTypes.map { parameterType ->
            dependencies.firstOrNull { dependency -> boxed(parameterType).isInstance(dependency) }
                ?: error("Missing test dependency for ${parameterType.name}")
        }
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(*arguments.toTypedArray()) as T
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Long.TYPE -> java.lang.Long::class.java
        else -> type
    }
}
