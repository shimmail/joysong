package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.identity.service.ManagementActor
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service

@Service
class DoctorProfileService(
    private val doctorService: DoctorService,
    private val doctorInstitutionService: DoctorInstitutionService
) {
    fun get(actor: ManagementActor): DoctorProfileView =
        profileFor(actor).toView(actor.userId)

    fun update(actor: ManagementActor, command: DoctorProfileUpdateCommand): DoctorProfileView {
        val doctorId = requireDoctorId(actor)
        require(command.name.isNotBlank()) { "name 不能为空" }
        val updated = (doctorService.findById(doctorId) ?: throw DoctorProfileNotFoundException())
            .copy(
                name = command.name.trim(),
                title = command.title.trim(),
                bio = command.bio.trim(),
                avatar = command.avatar.trim(),
                contactPhone = command.contactPhone.trim(),
                specialties = command.specialties.normalizedCommaSeparated(),
                credentials = command.credentials.trim(),
                credentialImages = command.credentialImages.normalizedCommaSeparated(),
                certificationTags = command.certificationTags.normalizedCommaSeparated()
            )
        return doctorService.save(updated).toView(actor.userId)
    }

    private fun profileFor(actor: ManagementActor): DoctorEntity =
        doctorService.findById(requireDoctorId(actor)) ?: throw DoctorProfileNotFoundException()

    private fun requireDoctorId(actor: ManagementActor): String {
        if (actor.isAdmin || "DOCTOR" !in actor.activeRoles) {
            throw AccessDeniedException("当前账号没有有效医生身份")
        }
        return actor.doctorId ?: actor.userId
    }

    private fun DoctorEntity.toView(userId: String): DoctorProfileView {
        val institutions = doctorInstitutionService.institutionsFor(id)
            .map { DoctorInstitutionView(it.id, it.name) }
        return DoctorProfileView(
            id = id,
            userId = userId,
            name = name,
            title = title,
            bio = bio,
            avatar = avatar,
            contactPhone = contactPhone,
            specialties = specialties,
            credentials = credentials,
            credentialImages = credentialImages,
            certificationTags = certificationTags,
            institutionId = institutionId,
            institutionName = institutionName,
            institutions = institutions,
            primaryInstitution = institutions.firstOrNull(),
            institutionCount = institutions.size,
            rating = rating,
            reviewCount = reviewCount,
            isVerified = isVerified,
            consultationCount = consultationCount,
            caseCount = caseCount
        )
    }

    private fun String.normalizedCommaSeparated(): String =
        split(',').map(String::trim).filter(String::isNotBlank).joinToString(",")
}

data class DoctorProfileUpdateCommand(
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val specialties: String,
    val credentials: String,
    val credentialImages: String,
    val certificationTags: String
)

data class DoctorInstitutionView(
    val id: String,
    val name: String
)

data class DoctorProfileView(
    val id: String,
    val userId: String,
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val specialties: String,
    val credentials: String,
    val credentialImages: String,
    val certificationTags: String,
    val institutionId: String,
    val institutionName: String,
    val institutions: List<DoctorInstitutionView>,
    val primaryInstitution: DoctorInstitutionView?,
    val institutionCount: Int,
    val rating: java.math.BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean,
    val consultationCount: Int,
    val caseCount: Int
)

class DoctorProfileNotFoundException : RuntimeException("医生档案不存在")
