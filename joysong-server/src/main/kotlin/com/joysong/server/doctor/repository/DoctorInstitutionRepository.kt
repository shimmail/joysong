package com.joysong.server.doctor.repository

import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DoctorInstitutionRepository : JpaRepository<DoctorInstitutionEntity, String> {
    fun findByDoctorIdOrderByCreatedAtAsc(doctorId: String): List<DoctorInstitutionEntity>
    fun findByInstitutionId(institutionId: String): List<DoctorInstitutionEntity>

    @Modifying
    @Query(value = "UPDATE doctor_institutions SET deleted_at = NULL, is_primary = :primary, status = 'PENDING', confirmed_by = NULL, confirmed_at = NULL, revoked_at = NULL WHERE doctor_id = :doctorId AND institution_id = :institutionId", nativeQuery = true)
    fun reactivate(@Param("doctorId") doctorId: String, @Param("institutionId") institutionId: String, @Param("primary") primary: Boolean): Int
}
