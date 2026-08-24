package com.joysong.server.institution.repository

import com.joysong.server.institution.entity.InstitutionProjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType

interface InstitutionProjectRepository : JpaRepository<InstitutionProjectEntity, String> {
    fun findTop8ByIsActiveTrueOrderBySalesCountDesc(): List<InstitutionProjectEntity>
    fun findByProjectId(projectId: String): List<InstitutionProjectEntity>
    fun findByInstitutionId(institutionId: String): List<InstitutionProjectEntity>
    fun findByInstitutionIdAndProjectId(institutionId: String, projectId: String): InstitutionProjectEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InstitutionProjectEntity p where p.id=:id")
    fun findForUpdate(@Param("id") id: String): InstitutionProjectEntity?
}
