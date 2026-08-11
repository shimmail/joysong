package com.joysong.server.institution.service

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class ManagedInstitutionProfileServiceTest {

    private val repository = mockk<InstitutionRepository>()
    private val accessService = ManagementAccessService(mockk(relaxed = true))
    private val institutionService = mockk<InstitutionService>(relaxed = true)
    private val service = ManagedInstitutionProfileService(repository, accessService, institutionService)

    @Test
    fun `list returns only the institutions managed by the actor`() {
        every { repository.findAll() } returns listOf(institution("managed"), institution("other"))

        val profiles = service.list(actor(managedInstitutionIds = setOf("managed")))

        assertEquals(listOf("managed"), profiles.map { it.id })
    }

    @Test
    fun `get rejects an institution outside the actor managed ids`() {
        val error = assertThrows(AccessDeniedException::class.java) {
            service.get(actor(managedInstitutionIds = setOf("managed")), "other")
        }

        assertEquals("只有该机构已确认的法人可以修改机构信息", error.message)
    }

    @Test
    fun `get maps persisted comma separated arrays to lists`() {
        every { repository.findById("managed") } returns java.util.Optional.of(
            institution(
                id = "managed",
                images = "cover-a,cover-b",
                credentialImages = "license-a,license-b",
                specialties = "skin,laser",
                tags = "premium,certified"
            )
        )

        val profile = service.get(actor(), "managed")

        assertEquals(listOf("cover-a", "cover-b"), profile.images)
        assertEquals(listOf("license-a", "license-b"), profile.credentialImages)
        assertEquals(listOf("skin", "laser"), profile.specialties)
        assertEquals(listOf("premium", "certified"), profile.tags)
    }

    @Test
    fun `update returns editable detail while retaining read only platform fields`() {
        val original = institution(
            id = "managed",
            rating = BigDecimal("4.80"),
            reviewCount = 23,
            isVerified = true,
            certificationTime = LocalDate.of(2026, 1, 2),
            projectCount = 9,
            doctorCount = 5,
            consultationCount = 80,
            userCount = 70,
            caseCount = 60,
            createdAt = LocalDateTime.of(2025, 1, 2, 3, 4),
            updatedAt = LocalDateTime.of(2026, 1, 2, 3, 4)
        )
        every { repository.updateManagedProfile(eq("managed"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { repository.findById("managed") } returns java.util.Optional.of(original.copy(
            name = "Renewed Clinic",
            address = "New address",
            city = "Shanghai",
            description = "New description",
            coverImage = "new-cover",
            images = "a,b",
            establishedYear = 2020,
            credentials = "New credentials",
            credentialImages = "license-a,license-b",
            specialties = "skin,laser",
            tags = "premium,featured",
            contactPhone = "123",
            businessHours = "9-5"
        ))

        val profile = service.update(actor(), "managed", command())

        assertEquals("Renewed Clinic", profile.name)
        assertEquals(listOf("a", "b"), profile.images)
        assertEquals(2020, profile.establishedYear)
        assertEquals("9-5", profile.businessHours)
        assertEquals(BigDecimal("4.80"), profile.rating)
        assertEquals(23, profile.reviewCount)
        assertTrue(profile.isVerified)
        assertEquals(LocalDate.of(2026, 1, 2), profile.certificationTime)
        assertEquals(9, profile.projectCount)
        assertEquals(5, profile.doctorCount)
        assertEquals(80, profile.consultationCount)
        assertEquals(70, profile.userCount)
        assertEquals(60, profile.caseCount)
        assertEquals(LocalDateTime.of(2025, 1, 2, 3, 4), profile.createdAt)
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4), profile.updatedAt)
        assertEquals(
            setOf(
                "name", "address", "city", "description", "coverImage", "images", "establishedYear",
                "credentials", "credentialImages", "specialties", "tags", "contactPhone", "businessHours"
            ),
            ManagedInstitutionProfileUpdateCommand::class.java.declaredFields.map { it.name }.toSet()
        )
    }

    @Test
    fun `update evicts institution caches only after transaction commit`() {
        stubSuccessfulUpdate()
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.update(actor(), "managed", command())

            verify(exactly = 0) { institutionService.evictInstitutionAndDiscoverCaches() }
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size)

            TransactionSynchronizationManager.getSynchronizations().single().afterCommit()

            verify(exactly = 1) { institutionService.evictInstitutionAndDiscoverCaches() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `update evicts institution caches immediately without a transaction`() {
        stubSuccessfulUpdate()

        service.update(actor(), "managed", command())

        verify(exactly = 1) { institutionService.evictInstitutionAndDiscoverCaches() }
    }

    private fun actor(managedInstitutionIds: Set<String> = setOf("managed")) = ManagementActor(
        userId = "legal-user",
        isAdmin = false,
        activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        doctorId = null,
        managedInstitutionIds = managedInstitutionIds,
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun command() = ManagedInstitutionProfileUpdateCommand(
        name = " Renewed Clinic ",
        address = " New address ",
        city = " Shanghai ",
        description = " New description ",
        coverImage = " new-cover ",
        images = listOf(" a ", "b", "a", ""),
        establishedYear = 2020,
        credentials = " New credentials ",
        credentialImages = listOf(" license-a ", "license-b", "license-a"),
        specialties = listOf(" skin ", "laser", "skin"),
        tags = listOf(" premium ", "featured", "premium"),
        contactPhone = " 123 ",
        businessHours = " 9-5 "
    )

    private fun stubSuccessfulUpdate() {
        every { repository.updateManagedProfile(eq("managed"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { repository.findById("managed") } returns java.util.Optional.of(institution("managed"))
    }

    private fun institution(
        id: String,
        images: String = "",
        credentialImages: String = "",
        specialties: String = "",
        tags: String = "",
        rating: BigDecimal = BigDecimal.ZERO,
        reviewCount: Int = 0,
        isVerified: Boolean = false,
        certificationTime: LocalDate? = null,
        projectCount: Int = 0,
        doctorCount: Int = 0,
        consultationCount: Int = 0,
        userCount: Int = 0,
        caseCount: Int = 0,
        createdAt: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0),
        updatedAt: LocalDateTime? = null
    ) = InstitutionEntity(
        id = id,
        name = "Original Clinic",
        images = images,
        credentialImages = credentialImages,
        specialties = specialties,
        tags = tags,
        rating = rating,
        reviewCount = reviewCount,
        isVerified = isVerified,
        certificationTime = certificationTime,
        projectCount = projectCount,
        doctorCount = doctorCount,
        consultationCount = consultationCount,
        userCount = userCount,
        caseCount = caseCount,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
