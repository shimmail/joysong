package com.joysong.server.institution.repository

import com.joysong.server.institution.entity.InstitutionProjectEntity
import org.springframework.data.jpa.repository.JpaRepository

interface InstitutionProjectRepository : JpaRepository<InstitutionProjectEntity, String> {
    fun findByProjectId(projectId: String): List<InstitutionProjectEntity>
    fun findByInstitutionId(institutionId: String): List<InstitutionProjectEntity>
    fun findByInstitutionIdAndProjectId(institutionId: String, projectId: String): InstitutionProjectEntity?
}
