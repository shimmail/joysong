package com.joysong.server.institution.service

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year

@Service
class ManagedInstitutionProfileService(
    private val institutionRepository: InstitutionRepository,
    private val managementAccessService: ManagementAccessService,
    private val institutionService: InstitutionService
) {
    fun list(actor: ManagementActor): List<ManagedInstitutionSummary> =
        institutionRepository.findAll()
            .asSequence()
            .filter { it.id in actor.managedInstitutionIds }
            .map { it.toSummary() }
            .toList()

    fun get(actor: ManagementActor, institutionId: String): ManagedInstitutionProfile {
        managementAccessService.requireInstitutionManaged(actor, institutionId)
        return institutionRepository.findById(institutionId).orElseThrow(::ManagedInstitutionProfileNotFoundException)
            .toProfile()
    }

    @Transactional
    fun update(
        actor: ManagementActor,
        institutionId: String,
        command: ManagedInstitutionProfileUpdateCommand
    ): ManagedInstitutionProfile {
        managementAccessService.requireInstitutionManaged(actor, institutionId)
        val normalized = command.normalized()
        require(
            institutionRepository.updateManagedProfile(
                id = institutionId,
                name = normalized.name,
                address = normalized.address,
                city = normalized.city,
                description = normalized.description,
                coverImage = normalized.coverImage,
                images = normalized.images.joinToString(","),
                establishedYear = normalized.establishedYear,
                credentials = normalized.credentials,
                credentialImages = normalized.credentialImages.joinToString(","),
                specialties = normalized.specialties.joinToString(","),
                tags = normalized.tags.joinToString(","),
                contactPhone = normalized.contactPhone,
                businessHours = normalized.businessHours
            ) == 1
        ) { "机构档案更新失败" }
        evictInstitutionCachesAfterCommit()
        return institutionRepository.findById(institutionId).orElseThrow(::ManagedInstitutionProfileNotFoundException)
            .toProfile()
    }

    private fun evictInstitutionCachesAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            institutionService.evictInstitutionAndDiscoverCaches()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                institutionService.evictInstitutionAndDiscoverCaches()
            }
        })
    }

    private fun ManagedInstitutionProfileUpdateCommand.normalized(): ManagedInstitutionProfileUpdateCommand {
        require(name.isNotBlank()) { "name 不能为空" }
        listOf(images, credentialImages, specialties, tags).forEach { values ->
            require(values.none { ',' in it }) { "列表项不能包含逗号" }
        }
        establishedYear?.let { year ->
            require(year in 1800..Year.now().value) { "establishedYear 必须在 1800 到当前年份之间" }
        }
        return copy(
            name = name.trim(),
            address = address.trim(),
            city = city.trim(),
            description = description.trim(),
            coverImage = coverImage.trim(),
            images = images.normalizedList(),
            credentials = credentials.trim(),
            credentialImages = credentialImages.normalizedList(),
            specialties = specialties.normalizedList(),
            tags = tags.normalizedList(),
            contactPhone = contactPhone.trim(),
            businessHours = businessHours.trim()
        )
    }

    private fun InstitutionEntity.toSummary() = ManagedInstitutionSummary(
        id = id,
        name = name,
        address = address,
        city = city,
        coverImage = coverImage,
        rating = rating,
        reviewCount = reviewCount,
        isVerified = isVerified
    )

    private fun InstitutionEntity.toProfile() = ManagedInstitutionProfile(
        id = id,
        name = name,
        address = address,
        city = city,
        description = description,
        coverImage = coverImage,
        images = images.toList(),
        establishedYear = establishedYear,
        credentials = credentials,
        credentialImages = credentialImages.toList(),
        specialties = specialties.toList(),
        tags = tags.toList(),
        contactPhone = contactPhone,
        businessHours = businessHours,
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

    private fun String.toList(): List<String> =
        split(',').normalizedList()

    private fun List<String>.normalizedList(): List<String> =
        map(String::trim).filter(String::isNotBlank).distinct()
}

data class ManagedInstitutionSummary(
    val id: String,
    val name: String,
    val address: String,
    val city: String,
    val coverImage: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean
)

data class ManagedInstitutionProfile(
    val id: String,
    val name: String,
    val address: String,
    val city: String,
    val description: String,
    val coverImage: String,
    val images: List<String>,
    val establishedYear: Int?,
    val credentials: String,
    val credentialImages: List<String>,
    val specialties: List<String>,
    val tags: List<String>,
    val contactPhone: String,
    val businessHours: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean,
    val certificationTime: LocalDate?,
    val projectCount: Int,
    val doctorCount: Int,
    val consultationCount: Int,
    val userCount: Int,
    val caseCount: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?
)

data class ManagedInstitutionProfileUpdateCommand(
    val name: String,
    val address: String,
    val city: String,
    val description: String,
    val coverImage: String,
    val images: List<String>,
    val establishedYear: Int?,
    val credentials: String,
    val credentialImages: List<String>,
    val specialties: List<String>,
    val tags: List<String>,
    val contactPhone: String,
    val businessHours: String
)

class ManagedInstitutionProfileNotFoundException : RuntimeException("机构档案不存在")
