package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionEntity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import java.math.BigDecimal

class DoctorProfileServiceTest {

    private val actor = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = setOf("institution-1", "institution-2"),
        manageableDoctorIds = setOf("doctor-1")
    )

    @Test
    fun `update replaces editable fields normalizes list fields and preserves platform fields`() {
        val existing = doctor()
        val doctorService = mockk<DoctorService>()
        val institutionService = mockk<DoctorInstitutionService>()
        var saved: DoctorEntity? = null
        every { doctorService.findById("doctor-1") } returns existing
        every { doctorService.save(any()) } answers {
            firstArg<DoctorEntity>().also { saved = it }
        }
        every { institutionService.institutionsFor("doctor-1") } returns institutions()

        val view = DoctorProfileService(doctorService, institutionService).update(
            actor,
            DoctorProfileUpdateCommand(
                name = "  李医生  ",
                title = "  主任医师  ",
                bio = "  擅长修复  ",
                avatar = "  avatar.png  ",
                contactPhone = "  13800000000  ",
                specialties = " 种植 , , 正畸 , ",
                credentials = "  医师资格证  ",
                credentialImages = "  a.png, , b.png  ",
                certificationTags = "  三甲 , , 专家  "
            )
        )

        assertEquals("李医生", view.name)
        assertEquals("种植,正畸", view.specialties)
        assertEquals("a.png,b.png", view.credentialImages)
        assertEquals("三甲,专家", view.certificationTags)
        assertEquals("主任医师", saved!!.title)
        assertEquals("擅长修复", saved!!.bio)
        assertEquals("avatar.png", saved!!.avatar)
        assertEquals("13800000000", saved!!.contactPhone)
        assertEquals("医师资格证", saved!!.credentials)
        assertEquals("institution-legacy", saved!!.institutionId)
        assertEquals("旧机构", saved!!.institutionName)
        assertEquals(BigDecimal("4.8"), saved!!.rating)
        assertEquals(13, saved!!.reviewCount)
        assertEquals(true, saved!!.isVerified)
        assertEquals(21, saved!!.consultationCount)
        assertEquals(8, saved!!.caseCount)
    }

    @Test
    fun `get returns the full view with institution summaries`() {
        val doctorService = mockk<DoctorService>()
        val institutionService = mockk<DoctorInstitutionService>()
        every { doctorService.findById("doctor-1") } returns doctor()
        every { institutionService.institutionsFor("doctor-1") } returns institutions()

        val view = DoctorProfileService(doctorService, institutionService).get(actor)

        assertEquals("doctor-1", view.id)
        assertEquals("doctor-1", view.userId)
        assertEquals("李医生", view.name)
        assertEquals("institution-legacy", view.institutionId)
        assertEquals("旧机构", view.institutionName)
        assertEquals(2, view.institutionCount)
        assertEquals(
            listOf(DoctorInstitutionView("institution-1", "第一医院"), DoctorInstitutionView("institution-2", "第二医院")),
            view.institutions
        )
        assertEquals(DoctorInstitutionView("institution-1", "第一医院"), view.primaryInstitution)
        assertEquals(BigDecimal("4.8"), view.rating)
        assertEquals(13, view.reviewCount)
        assertEquals(true, view.isVerified)
        assertEquals(21, view.consultationCount)
        assertEquals(8, view.caseCount)
    }

    @Test
    fun `update rejects blank name`() {
        val service = DoctorProfileService(mockk(relaxed = true), mockk(relaxed = true))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.update(actor, command(name = "   "))
        }

        assertEquals("name 不能为空", error.message)
    }

    @Test
    fun `non doctor actor is denied`() {
        val service = DoctorProfileService(mockk(relaxed = true), mockk(relaxed = true))
        val nonDoctor = actor.copy(activeRoles = setOf("CONSULTANT"), doctorId = null)

        val error = assertThrows(AccessDeniedException::class.java) {
            service.get(nonDoctor)
        }

        assertEquals("当前账号没有有效医生身份", error.message)
    }

    @Test
    fun `active doctor without profile receives explicit not found error`() {
        val doctorService = mockk<DoctorService>()
        every { doctorService.findById("doctor-1") } returns null
        val service = DoctorProfileService(doctorService, mockk(relaxed = true))

        val error = assertThrows(DoctorProfileNotFoundException::class.java) {
            service.get(actor.copy(doctorId = null))
        }

        assertEquals("医生档案不存在", error.message)
    }

    private fun command(name: String = "李医生") = DoctorProfileUpdateCommand(
        name = name,
        title = "主任医师",
        bio = "擅长修复",
        avatar = "avatar.png",
        contactPhone = "13800000000",
        specialties = "种植,正畸",
        credentials = "医师资格证",
        credentialImages = "a.png,b.png",
        certificationTags = "三甲,专家"
    )

    private fun doctor() = DoctorEntity(
        id = "doctor-1",
        name = "李医生",
        title = "副主任医师",
        bio = "旧简介",
        avatar = "old.png",
        contactPhone = "13900000000",
        institutionId = "institution-legacy",
        institutionName = "旧机构",
        rating = BigDecimal("4.8"),
        reviewCount = 13,
        specialties = "旧专长",
        isVerified = true,
        consultationCount = 21,
        credentials = "旧资质",
        credentialImages = "old-credential.png",
        caseCount = 8,
        certificationTags = "旧标签"
    )

    private fun institutions() = listOf(
        InstitutionEntity(id = "institution-1", name = "第一医院"),
        InstitutionEntity(id = "institution-2", name = "第二医院")
    )
}
