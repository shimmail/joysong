package com.joysong.server.discover.repository

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.entity.DoctorProjectId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import java.math.BigDecimal

interface PublicDoctorProjectView {
    val doctorId: String
    val projectId: String
    val institutionProjectId: String
    val price: BigDecimal
}

interface DoctorProjectRepository : JpaRepository<DoctorProjectEntity, DoctorProjectId> {
    fun findByDoctorId(doctorId: String): List<DoctorProjectEntity>
    fun findByProjectId(projectId: String): List<DoctorProjectEntity>
    fun findByInstitutionProjectId(institutionProjectId: String): List<DoctorProjectEntity>
    @Query("select d.doctorId from DoctorProjectEntity d where d.institutionProjectId=:institutionProjectId order by d.doctorId")
    fun findDoctorIdsByInstitutionProjectId(
        @Param("institutionProjectId") institutionProjectId: String
    ): List<String>
    @Query(
        value = """
            SELECT dp.* FROM doctor_projects dp
            JOIN institution_projects ip ON ip.id = dp.institution_project_id
            WHERE dp.institution_project_id = :institutionProjectId
              AND dp.is_active = TRUE
              AND ip.is_active = TRUE AND ip.deleted_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM doctor_institutions di
                  WHERE di.doctor_id = dp.doctor_id
                    AND di.institution_id = ip.institution_id
                    AND di.status = 'APPROVED'
                    AND di.revoked_at IS NULL
                    AND di.deleted_at IS NULL
              )
        """,
        nativeQuery = true
    )
    fun findActiveByInstitutionProjectId(
        @Param("institutionProjectId") institutionProjectId: String
    ): List<DoctorProjectEntity>
    @Query(
        value = """
            SELECT dp.doctor_id AS doctorId,
                   dp.project_id AS projectId,
                   dp.institution_project_id AS institutionProjectId,
                   dp.price AS price
            FROM doctor_projects dp
            JOIN institution_projects ip ON ip.id = dp.institution_project_id
            WHERE dp.institution_project_id IN (:institutionProjectIds)
              AND dp.is_active = TRUE
              AND ip.is_active = TRUE AND ip.deleted_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM doctor_institutions di
                  WHERE di.doctor_id = dp.doctor_id
                    AND di.institution_id = ip.institution_id
                    AND di.status = 'APPROVED'
                    AND di.revoked_at IS NULL
                    AND di.deleted_at IS NULL
              )
            ORDER BY dp.institution_project_id, dp.doctor_id
        """,
        nativeQuery = true
    )
    fun findPublicByInstitutionProjectIds(
        @Param("institutionProjectIds") institutionProjectIds: Collection<String>
    ): List<PublicDoctorProjectView>
    @Query(
        value = """
            SELECT dp.doctor_id AS doctorId,
                   dp.project_id AS projectId,
                   dp.institution_project_id AS institutionProjectId,
                   dp.price AS price
            FROM doctor_projects dp
            JOIN institution_projects ip ON ip.id = dp.institution_project_id
            WHERE dp.doctor_id = :doctorId
              AND dp.is_active = TRUE
              AND ip.is_active = TRUE AND ip.deleted_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM doctor_institutions di
                  WHERE di.doctor_id = dp.doctor_id
                    AND di.institution_id = ip.institution_id
                    AND di.status = 'APPROVED'
                    AND di.revoked_at IS NULL
                    AND di.deleted_at IS NULL
              )
            ORDER BY dp.institution_project_id
        """,
        nativeQuery = true
    )
    fun findPublicByDoctorId(@Param("doctorId") doctorId: String): List<PublicDoctorProjectView>
    fun findByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): DoctorProjectEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DoctorProjectEntity d where d.doctorId=:doctorId and d.institutionProjectId=:institutionProjectId")
    fun findForUpdate(@Param("doctorId") doctorId: String, @Param("institutionProjectId") institutionProjectId: String): DoctorProjectEntity?
    fun existsByDoctorIdAndInstitutionProjectId(doctorId: String, institutionProjectId: String): Boolean
    fun deleteByInstitutionProjectId(institutionProjectId: String)
}
