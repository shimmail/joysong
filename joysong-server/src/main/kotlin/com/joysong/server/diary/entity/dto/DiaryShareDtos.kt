package com.joysong.server.diary.entity.dto

import com.joysong.server.diary.entity.DiaryEntity
import java.time.LocalDate
import java.time.LocalDateTime

data class CreateDiaryShareRequest(val expiresInDays: Int? = null)

data class DiarySharePreview(val title: String, val authorName: String, val coverImage: String)

data class DiaryShareResponse(
    val shareUrl: String,
    val token: String,
    val expiresAt: LocalDateTime?,
    val preview: DiarySharePreview
)

data class PublicDiaryAuthor(val id: String, val name: String, val avatar: String)
data class PublicDiaryAssociation(val id: String, val name: String)

data class PublicDiaryShareResponse(
    val diaryId: String,
    val title: String,
    val content: String,
    val author: PublicDiaryAuthor,
    val publishDate: LocalDate,
    val images: List<String>,
    val beforeImages: List<String>,
    val afterImages: List<String>,
    val tags: List<String>,
    val rating: Int,
    val likeCount: Int,
    val commentCount: Int,
    val project: PublicDiaryAssociation?,
    val doctor: PublicDiaryAssociation?,
    val institution: PublicDiaryAssociation?
)

fun DiaryEntity.toPublicShareResponse(): PublicDiaryShareResponse = PublicDiaryShareResponse(
    diaryId = id,
    title = title,
    content = content,
    author = PublicDiaryAuthor(userId, authorName, authorAvatar),
    publishDate = publishDate,
    images = imageUrls,
    beforeImages = beforeImageUrls,
    afterImages = afterImageUrls,
    tags = tags.split(',').map(String::trim).filter(String::isNotEmpty),
    rating = rating,
    likeCount = likeCount,
    commentCount = commentCount,
    project = projectId.takeIf(String::isNotBlank)?.let { PublicDiaryAssociation(it, projectName) },
    doctor = doctorId.takeIf(String::isNotBlank)?.let { PublicDiaryAssociation(it, doctorName) },
    institution = institutionId.takeIf(String::isNotBlank)?.let { PublicDiaryAssociation(it, institutionName) }
)
