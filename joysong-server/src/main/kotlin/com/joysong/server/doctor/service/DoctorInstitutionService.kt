package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.identity.service.DoctorInstitutionRequestConflictException
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DoctorInstitutionService(
    private val repository: DoctorInstitutionRepository,
    private val institutionRepository: InstitutionRepository
) {
    fun findByDoctorId(doctorId: String): List<DoctorInstitutionEntity> = repository.findByDoctorIdOrderByCreatedAtAsc(doctorId)
    fun findByInstitutionId(institutionId: String): List<DoctorInstitutionEntity> = repository.findByInstitutionId(institutionId)
    fun primaryFor(doctorId: String): DoctorInstitutionEntity? = findByDoctorId(doctorId).firstOrNull { it.isPrimary }

    fun institutionsFor(doctorId: String): List<InstitutionEntity> {
        val relations = findByDoctorId(doctorId)
            .filter { it.status == "APPROVED" }
            .sortedWith(compareByDescending<DoctorInstitutionEntity> { it.isPrimary }.thenBy { it.createdAt })
        val byId = institutionRepository.findAllById(relations.map { it.institutionId }).associateBy { it.id }
        return relations.mapNotNull { byId[it.institutionId] }
    }

    @Transactional
    fun sync(doctorId: String, requestedIds: Collection<String>, requestedPrimaryId: String?): DoctorInstitutionSelection {
        val ids = requestedIds.map(String::trim).filter(String::isNotBlank).distinct()
        val normalizedPrimaryId = requestedPrimaryId?.trim()?.takeIf(String::isNotEmpty)
        val current = findByDoctorId(doctorId)
        val approved = current.filter { it.status == "APPROVED" && it.revokedAt == null && it.deletedAt == null }
        val approvedIds = approved.map { it.institutionId }

        if (ids.isEmpty() && normalizedPrimaryId == null) {
            val institutions = institutionRepository.findAllById(approvedIds).associateBy { it.id }
            return DoctorInstitutionSelection(
                relations = current,
                institutions = institutions,
                primaryInstitutionId = approved.firstOrNull { it.isPrimary }?.institutionId ?: approvedIds.firstOrNull()
            )
        }
        if (ids.toSet() != approvedIds.toSet()) {
            throw DoctorInstitutionRequestConflictException("医生机构关系只能通过关系申请变更")
        }
        val primaryId = normalizedPrimaryId ?: approved.firstOrNull { it.isPrimary }?.institutionId ?: approvedIds.firstOrNull()
        if (primaryId !in approvedIds) {
            throw DoctorInstitutionRequestConflictException("主机构必须在已批准机构中")
        }
        val institutions = institutionRepository.findAllById(approvedIds).associateBy { it.id }
        approved.forEach { relationship ->
            val shouldBePrimary = relationship.institutionId == primaryId
            if (relationship.isPrimary != shouldBePrimary) {
                repository.save(relationship.copy(isPrimary = shouldBePrimary))
            }
        }
        return DoctorInstitutionSelection(
            relations = findByDoctorId(doctorId),
            institutions = institutions,
            primaryInstitutionId = primaryId
        )
    }
}

data class DoctorInstitutionSelection(
    val relations: List<DoctorInstitutionEntity>,
    val institutions: Map<String, InstitutionEntity>,
    val primaryInstitutionId: String?
)
