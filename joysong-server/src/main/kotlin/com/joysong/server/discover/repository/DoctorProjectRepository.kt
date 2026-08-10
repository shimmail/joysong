package com.joysong.server.discover.repository

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.entity.DoctorProjectId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DoctorProjectRepository : JpaRepository<DoctorProjectEntity, DoctorProjectId> {
    fun findByDoctorId(doctorId: String): List<DoctorProjectEntity>
    fun findByProjectId(projectId: String): List<DoctorProjectEntity>
    fun findByInstitutionProjectId(institutionProjectId: String): List<DoctorProjectEntity>
    @Query(
        value = """
            SELECT dp.* FROM doctor_projects dp
            JOIN institution_projects ip ON ip.id = dp.institution_project_id
            JOIN doctor_institutions di
              ON di.doctor_id = dp.doctor_id AND di.institution_id = ip.institution_id
            WHERE dp.institution_project_id = :institutionProjectId
              AND di.status = 'APPROVED' AND di.revoked_at IS NULL AND di.deleted_at IS NULL
        """,
        nativeQuery = true
    )
    fun findActiveByInstitutionProjectId(
        @Param("institutionProjectId") institutionProjectId: String
    ): List<DoctorProjectEntity>
    fun findByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): DoctorProjectEntity?
    fun existsByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): Boolean
    fun deleteByInstitutionProjectId(institutionProjectId: String)
}
