package com.joysong.server.doctor.repository

import com.joysong.server.doctor.entity.DoctorEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DoctorRepository : JpaRepository<DoctorEntity, String> {
    fun findByNameContainingOrSpecialtiesContaining(name: String, specialties: String): List<DoctorEntity>
    fun findByNameContaining(name: String): List<DoctorEntity>
    fun findByInstitutionId(institutionId: String): List<DoctorEntity>

    @Query("SELECT d FROM DoctorEntity d WHERE d.name LIKE %:keyword% OR d.id = :keyword")
    fun searchDoctors(@Param("keyword") keyword: String): List<DoctorEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DoctorEntity doctor
        SET doctor.consultationCount = COALESCE(doctor.consultationCount, 0) + 1
        WHERE doctor.id = :id AND doctor.deletedAt IS NULL
        """
    )
    fun incrementConsultationCount(@Param("id") id: String): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE DoctorEntity doctor
        SET doctor.name = :name,
            doctor.title = :title,
            doctor.bio = :bio,
            doctor.avatar = :avatar,
            doctor.contactPhone = :contactPhone,
            doctor.specialties = :specialties,
            doctor.credentials = :credentials,
            doctor.credentialImages = :credentialImages,
            doctor.certificationTags = :certificationTags
        WHERE doctor.id = :id
        """
    )
    fun updateEditableProfile(
        @Param("id") id: String,
        @Param("name") name: String,
        @Param("title") title: String,
        @Param("bio") bio: String,
        @Param("avatar") avatar: String,
        @Param("contactPhone") contactPhone: String,
        @Param("specialties") specialties: String,
        @Param("credentials") credentials: String,
        @Param("credentialImages") credentialImages: String,
        @Param("certificationTags") certificationTags: String
    ): Int
}
