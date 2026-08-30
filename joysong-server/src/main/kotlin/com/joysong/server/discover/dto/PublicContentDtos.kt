package com.joysong.server.discover.dto

import com.joysong.server.article.entity.ArticleEntity
import com.joysong.server.banner.entity.BannerEntity
import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.project.entity.ProjectEntity
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Explicit public API DTOs for home/discover content.
 *
 * Persistence-only fields such as deletedAt and mutable audit timestamps are
 * intentionally excluded so schema changes cannot silently alter the mobile
 * API contract.
 */
data class BannerResponse(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val accentColor: String,
    val sortOrder: Int
)

data class ProjectResponse(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val tags: String,
    val categoryTags: String,
    val coverImage: String,
    val images: String,
    val imageUrls: List<String>,
    val referencePrice: BigDecimal,
    val currency: String = com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE,
    val slogan: String,
    val detailContent: String?,
    val salesCount: Int,
    val caseCount: Int,
    val rating: BigDecimal,
    val reviewCount: Int
)

data class ArticleResponse(
    val id: String,
    val title: String,
    val authorName: String,
    val summary: String,
    val coverImage: String,
    val publishDate: LocalDate,
    val content: String,
    val readCount: Int,
    val doctorId: String
)

data class DiaryResponse(
    val id: String,
    val title: String,
    val userId: String,
    val authorName: String,
    val authorAvatar: String,
    val content: String,
    val coverImage: String,
    val images: String,
    val imageUrls: List<String>,
    val likeCount: Int,
    val commentCount: Int,
    val tags: String,
    val publishDate: LocalDate,
    val status: String,
    val doctorId: String,
    val projectId: String,
    val institutionProjectId: String,
    val institutionId: String,
    val projectName: String,
    val doctorName: String,
    val institutionName: String,
    val beforeImages: String,
    val beforeImageUrls: List<String>,
    val afterImages: String,
    val afterImageUrls: List<String>,
    val rating: Int,
    val favoriteCount: Int
)

data class DoctorResponse(
    val id: String,
    val name: String,
    val title: String,
    val bio: String,
    val avatar: String,
    val contactPhone: String,
    val institutionId: String,
    val institutionName: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val specialties: String,
    val isVerified: Boolean,
    val consultationCount: Int,
    val credentials: String,
    val credentialImages: String,
    val caseCount: Int,
    val certificationTags: String,
    val projectPrice: BigDecimal? = null
)

data class InstitutionResponse(
    val id: String,
    val name: String,
    val address: String,
    val city: String,
    val description: String,
    val coverImage: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val isVerified: Boolean,
    val images: String,
    val projectCount: Int,
    val doctorCount: Int,
    val consultationCount: Int,
    val credentials: String,
    val establishedYear: Int?,
    val certificationTime: LocalDate?,
    val userCount: Int,
    val credentialImages: String,
    val specialties: String,
    val tags: String,
    val contactPhone: String,
    val businessHours: String,
    val caseCount: Int
)

data class InstitutionProjectResponse(
    val id: String,
    val institutionId: String,
    val projectId: String,
    val name: String?,
    val category: String?,
    val description: String?,
    val rating: BigDecimal?,
    val reviewCount: Int?,
    val tags: String?,
    val slogan: String?,
    val detailContent: String?,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val currency: String = com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE,
    val coverImage: String,
    val images: String,
    val salesCount: Int,
    val caseCount: Int,
    val isActive: Boolean
)

fun BannerEntity.toResponse() = BannerResponse(
    id = id,
    title = title,
    subtitle = subtitle,
    imageUrl = imageUrl,
    accentColor = accentColor,
    sortOrder = sortOrder
)

fun ProjectEntity.toResponse() = ProjectResponse(
    id = id,
    name = name,
    category = category,
    description = description,
    tags = tags,
    categoryTags = categoryTags,
    coverImage = coverImage,
    images = images,
    imageUrls = images.toPublicImageUrls(),
    referencePrice = referencePrice,
    currency = currency,
    slogan = slogan,
    detailContent = detailContent,
    salesCount = salesCount,
    caseCount = caseCount,
    rating = rating,
    reviewCount = reviewCount
)

fun ArticleEntity.toResponse() = ArticleResponse(
    id = id,
    title = title,
    authorName = authorName,
    summary = summary,
    coverImage = coverImage,
    publishDate = publishDate,
    content = content,
    readCount = readCount,
    doctorId = doctorId
)

fun DiaryEntity.toResponse() = DiaryResponse(
    id = id,
    title = title,
    userId = userId,
    authorName = authorName,
    authorAvatar = authorAvatar,
    content = content,
    coverImage = coverImage,
    images = images,
    imageUrls = imageUrls,
    likeCount = likeCount,
    commentCount = commentCount,
    tags = tags,
    publishDate = publishDate,
    status = status,
    doctorId = doctorId,
    projectId = projectId,
    institutionProjectId = institutionProjectId,
    institutionId = institutionId,
    projectName = projectName,
    doctorName = doctorName,
    institutionName = institutionName,
    beforeImages = beforeImages,
    beforeImageUrls = beforeImageUrls,
    afterImages = afterImages,
    afterImageUrls = afterImageUrls,
    rating = rating,
    favoriteCount = favoriteCount
)

fun DoctorEntity.toResponse(projectPrice: BigDecimal? = null) = DoctorResponse(
    id = id,
    name = name,
    title = title,
    bio = bio,
    avatar = avatar,
    contactPhone = contactPhone,
    institutionId = institutionId,
    institutionName = institutionName,
    rating = rating,
    reviewCount = reviewCount,
    specialties = specialties,
    isVerified = isVerified,
    consultationCount = consultationCount,
    credentials = credentials,
    credentialImages = credentialImages,
    caseCount = caseCount,
    certificationTags = certificationTags,
    projectPrice = projectPrice
)

fun InstitutionEntity.toResponse() = InstitutionResponse(
    id = id,
    name = name,
    address = address,
    city = city,
    description = description,
    coverImage = coverImage,
    rating = rating,
    reviewCount = reviewCount,
    isVerified = isVerified,
    images = images,
    projectCount = projectCount,
    doctorCount = doctorCount,
    consultationCount = consultationCount,
    credentials = credentials,
    establishedYear = establishedYear,
    certificationTime = certificationTime,
    userCount = userCount,
    credentialImages = credentialImages,
    specialties = specialties,
    tags = tags,
    contactPhone = contactPhone,
    businessHours = businessHours,
    caseCount = caseCount
)

fun InstitutionProjectEntity.toResponse() = InstitutionProjectResponse(
    id = id,
    institutionId = institutionId,
    projectId = projectId,
    name = name,
    category = category,
    description = description,
    rating = rating,
    reviewCount = reviewCount,
    tags = tags,
    slogan = slogan,
    detailContent = detailContent,
    price = price,
    originalPrice = originalPrice,
    currency = currency,
    coverImage = coverImage.orEmpty(),
    images = images.orEmpty(),
    salesCount = salesCount,
    caseCount = caseCount,
    isActive = isActive
)

private fun String.toPublicImageUrls(): List<String> =
    split(',').map(String::trim).filter(String::isNotEmpty)
