package com.joysong.server.legal.entity

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

enum class LegalDocumentType(@get:JsonValue val slug: String) {
    USER_AGREEMENT("user-agreement"),
    PRIVACY_POLICY("privacy-policy");

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromSlug(value: String) = entries.firstOrNull { it.slug == value }
            ?: throw IllegalArgumentException("不支持的协议类型")
    }
}

enum class LegalDocumentLocale(@get:JsonValue val tag: String) {
    ZH_CN("zh-CN"), EN_US("en-US");

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromTag(value: String) = entries.firstOrNull { it.tag == value }
            ?: throw IllegalArgumentException("不支持的协议语言")
    }
}

enum class LegalDocumentStatus { DRAFT, PUBLISHED, SUPERSEDED }

@Converter
class LegalDocumentLocaleConverter : AttributeConverter<LegalDocumentLocale, String> {
    override fun convertToDatabaseColumn(attribute: LegalDocumentLocale?): String? = attribute?.tag

    override fun convertToEntityAttribute(dbData: String?): LegalDocumentLocale? =
        dbData?.let(LegalDocumentLocale::fromTag)
}

@Entity
@Table(name = "legal_document_releases")
data class LegalDocumentReleaseEntity(
    @Id val id: String,
    @Enumerated(EnumType.STRING) @Column(name = "document_type") val documentType: LegalDocumentType,
    val version: Int,
    @Enumerated(EnumType.STRING) var status: LegalDocumentStatus,
    @Column(name = "change_summary") var changeSummary: String = "",
    @Column(name = "published_at") var publishedAt: LocalDateTime? = null,
    @Column(name = "published_by") var publishedBy: String? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "created_by") val createdBy: String,
    @Column(name = "updated_at") var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_by") var updatedBy: String,
    @Version @Column(name = "lock_version") var lockVersion: Long = 0
)

@Entity
@Table(name = "legal_document_contents")
data class LegalDocumentContentEntity(
    @Id val id: String,
    @Column(name = "release_id") val releaseId: String,
    @Convert(converter = LegalDocumentLocaleConverter::class) val locale: LegalDocumentLocale,
    var title: String = "",
    @Column(name = "content_html", columnDefinition = "MEDIUMTEXT") var contentHtml: String,
    @Column(name = "content_sha256", columnDefinition = "CHAR(64)") var contentSha256: String,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") var updatedAt: LocalDateTime = LocalDateTime.now()
)
