package com.joysong.server.article.dto

import com.joysong.server.article.entity.ArticleEntity
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime

data class DoctorArticleUpsertRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:Size(max = 1000) val summary: String,
    @field:Size(max = 500) val coverImage: String,
    val publishDate: LocalDate,
    @field:Size(max = 100000) val content: String
)

data class DoctorArticleView(
    val id: String,
    val title: String,
    val authorName: String,
    val summary: String,
    val coverImage: String,
    val publishDate: LocalDate,
    val content: String,
    val readCount: Int,
    val doctorId: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?
) {
    companion object {
        fun from(entity: ArticleEntity) = DoctorArticleView(
            entity.id, entity.title, entity.authorName, entity.summary, entity.coverImage,
            entity.publishDate, entity.content, entity.readCount, entity.doctorId,
            entity.createdAt, entity.updatedAt
        )
    }
}
