package com.joysong.server.institution.repository

import com.joysong.server.institution.entity.InstitutionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InstitutionRepository : JpaRepository<InstitutionEntity, String> {
    fun findByNameContainingOrCityContaining(name: String, city: String): List<InstitutionEntity>

    @Query(
        value = """
            SELECT COUNT(*)
            FROM institutions deleted
            WHERE deleted.deleted_at IS NOT NULL
              AND CHAR_LENGTH(TRIM(deleted.name)) >= 2
              AND LOCATE(LOWER(TRIM(deleted.name)), LOWER(:query)) > 0
              AND NOT EXISTS (
                  SELECT 1
                  FROM institutions active
                  WHERE active.deleted_at IS NULL
                    AND LOWER(TRIM(active.name)) = LOWER(TRIM(deleted.name))
              )
        """,
        nativeQuery = true
    )
    fun countSoftDeletedNamesMentionedInQuery(@Param("query") query: String): Long

    @Query("SELECT i FROM InstitutionEntity i WHERE i.name LIKE %:keyword% OR i.id = :keyword")
    fun searchInstitutions(@Param("keyword") keyword: String): List<InstitutionEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE InstitutionEntity institution
        SET institution.name = :name,
            institution.address = :address,
            institution.city = :city,
            institution.description = :description,
            institution.coverImage = :coverImage,
            institution.images = :images,
            institution.establishedYear = :establishedYear,
            institution.credentials = :credentials,
            institution.credentialImages = :credentialImages,
            institution.specialties = :specialties,
            institution.tags = :tags,
            institution.contactPhone = :contactPhone,
            institution.businessHours = :businessHours
        WHERE institution.id = :id
        """
    )
    fun updateManagedProfile(
        @Param("id") id: String,
        @Param("name") name: String,
        @Param("address") address: String,
        @Param("city") city: String,
        @Param("description") description: String,
        @Param("coverImage") coverImage: String,
        @Param("images") images: String,
        @Param("establishedYear") establishedYear: Int?,
        @Param("credentials") credentials: String,
        @Param("credentialImages") credentialImages: String,
        @Param("specialties") specialties: String,
        @Param("tags") tags: String,
        @Param("contactPhone") contactPhone: String,
        @Param("businessHours") businessHours: String
    ): Int
}
