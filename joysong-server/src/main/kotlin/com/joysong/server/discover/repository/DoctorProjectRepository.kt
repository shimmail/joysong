package com.joysong.server.discover.repository

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.entity.DoctorProjectId
import org.springframework.data.jpa.repository.JpaRepository

interface DoctorProjectRepository : JpaRepository<DoctorProjectEntity, DoctorProjectId> {
    fun findByDoctorId(doctorId: String): List<DoctorProjectEntity>
    fun findByProjectId(projectId: String): List<DoctorProjectEntity>
    fun findByInstitutionProjectId(institutionProjectId: String): List<DoctorProjectEntity>
    fun findByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): DoctorProjectEntity?
    fun existsByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): Boolean
    fun deleteByInstitutionProjectId(institutionProjectId: String)
}
