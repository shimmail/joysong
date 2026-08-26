package com.joysong.server.institution.repository

import com.joysong.server.institution.entity.InstitutionProjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType

interface InstitutionProjectIdentity {
    val id: String
    val institutionId: String
    val projectId: String
    val version: Long
}

interface InstitutionProjectRepository : JpaRepository<InstitutionProjectEntity, String> {
    fun findTop8ByIsActiveTrueOrderBySalesCountDesc(): List<InstitutionProjectEntity>
    fun findByIsActiveTrueOrderBySalesCountDesc(): List<InstitutionProjectEntity>
    fun findByProjectId(projectId: String): List<InstitutionProjectEntity>
    fun findByInstitutionId(institutionId: String): List<InstitutionProjectEntity>
    fun findByInstitutionIdAndProjectId(institutionId: String, projectId: String): InstitutionProjectEntity?
    @Query(
        "select p.id as id, p.institutionId as institutionId, p.projectId as projectId, p.version as version " +
            "from InstitutionProjectEntity p where p.id=:id"
    )
    fun findIdentityById(@Param("id") id: String): InstitutionProjectIdentity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from InstitutionProjectEntity p where p.id=:id")
    fun findForUpdate(@Param("id") id: String): InstitutionProjectEntity?
}
