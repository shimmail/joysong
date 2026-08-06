package com.joysong.server.doctor.repository

import com.joysong.server.doctor.entity.DoctorEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DoctorRepository : JpaRepository<DoctorEntity, String> {
    fun findByNameContainingOrSpecialtiesContaining(name: String, specialties: String): List<DoctorEntity>
    fun findByNameContaining(name: String): List<DoctorEntity>
    fun findByInstitutionId(institutionId: String): List<DoctorEntity>

    @Query("SELECT d FROM DoctorEntity d WHERE d.name LIKE %:keyword% OR d.id = :keyword")
    fun searchDoctors(@Param("keyword") keyword: String): List<DoctorEntity>
}
