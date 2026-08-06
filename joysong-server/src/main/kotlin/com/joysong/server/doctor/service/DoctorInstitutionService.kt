package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
        val institutions = institutionRepository.findAllById(ids).associateBy { it.id }
        require(institutions.size == ids.size) { "存在无效的机构" }
        require(requestedPrimaryId.isNullOrBlank() || requestedPrimaryId in ids) { "主机构必须在已绑定机构中" }

        val primaryId = requestedPrimaryId?.takeIf { it.isNotBlank() } ?: ids.firstOrNull()
        val active = findByDoctorId(doctorId)
        active.filter { it.institutionId !in ids }.forEach {
            it.deletedAt = java.time.LocalDateTime.now()
            repository.save(it)
        }
        ids.forEach { institutionId ->
            val isPrimary = institutionId == primaryId
            val existing = active.firstOrNull { it.institutionId == institutionId }
            when {
                existing != null -> repository.save(existing.copy(
                    isPrimary = isPrimary,
                    status = if (existing.status == "REVOKED") "PENDING" else existing.status,
                    confirmedBy = if (existing.status == "REVOKED") null else existing.confirmedBy,
                    confirmedAt = if (existing.status == "REVOKED") null else existing.confirmedAt,
                    revokedAt = null,
                    deletedAt = null
                ))
                repository.reactivate(doctorId, institutionId, isPrimary) == 0 -> repository.save(
                    DoctorInstitutionEntity(UUID.randomUUID().toString(), doctorId, institutionId, isPrimary)
                )
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
