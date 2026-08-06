package com.joysong.server.order.repository

import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DoctorInstitutionProjectConfigRepository : JpaRepository<DoctorInstitutionProjectConfigEntity, String> {
    fun findByInstitutionProjectId(institutionProjectId: String): List<DoctorInstitutionProjectConfigEntity>
    fun findByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): DoctorInstitutionProjectConfigEntity?
    @Query(
        value = "SELECT * FROM doctor_institution_project_configs WHERE doctor_id = :doctorId AND institution_project_id = :institutionProjectId LIMIT 1",
        nativeQuery = true
    )
    fun findByDoctorIdAndInstitutionProjectIdIncludeDeleted(
        @Param("doctorId") doctorId: String,
        @Param("institutionProjectId") institutionProjectId: String
    ): DoctorInstitutionProjectConfigEntity?
    fun deleteByInstitutionProjectId(institutionProjectId: String)
}
