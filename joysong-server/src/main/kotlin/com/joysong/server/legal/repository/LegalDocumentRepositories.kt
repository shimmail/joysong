package com.joysong.server.legal.repository

import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface LegalDocumentReleaseRepository : JpaRepository<LegalDocumentReleaseEntity, String> {
    fun findAllByDocumentTypeOrderByVersionDesc(type: LegalDocumentType): List<LegalDocumentReleaseEntity>
    fun findFirstByDocumentTypeAndStatus(
        type: LegalDocumentType,
        status: LegalDocumentStatus
    ): LegalDocumentReleaseEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LegalDocumentReleaseEntity r WHERE r.documentType = :type ORDER BY r.version DESC")
    fun findAllByDocumentTypeForUpdate(type: LegalDocumentType): List<LegalDocumentReleaseEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LegalDocumentReleaseEntity r WHERE r.id = :id")
    fun findByIdForUpdate(id: String): LegalDocumentReleaseEntity?
}

interface LegalDocumentContentRepository : JpaRepository<LegalDocumentContentEntity, String> {
    fun findAllByReleaseIdOrderByLocaleAsc(releaseId: String): List<LegalDocumentContentEntity>
    fun findAllByReleaseIdIn(releaseIds: Collection<String>): List<LegalDocumentContentEntity>
}
