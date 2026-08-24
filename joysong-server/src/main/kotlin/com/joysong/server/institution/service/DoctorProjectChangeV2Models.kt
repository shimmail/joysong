package com.joysong.server.institution.service

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.springframework.http.HttpStatus
import java.time.Instant

enum class ProjectChangeErrorCode {
    EDIT_BASE_STALE,
    APPROVAL_BASE_STALE,
    INHERITANCE_SOURCE_STALE,
    PRICING_POLICY_STALE,
    FORCE_BASE_STALE,
    REQUEST_ALREADY_PENDING,
    REQUEST_ALREADY_HANDLED,
    CLIENT_UPGRADE_REQUIRED,
    FORCE_NOT_APPLICABLE,
    PROJECT_PAYLOAD_INVALID,
    REQUEST_SNAPSHOT_INVALID,
    INSTITUTION_PROJECT_VERSION_STALE
}

class ProjectChangeContractException(
    val status: HttpStatus,
    val errorCode: ProjectChangeErrorCode,
    message: String
) : RuntimeException(message)

@JsonPropertyOrder("schemaVersion", "association", "rawOverrides", "effective", "source")
data class InstitutionProjectSnapshotV2(
    val schemaVersion: Int,
    val association: ProjectAssociationSnapshot,
    val rawOverrides: ProjectRawOverridesSnapshot,
    val effective: ProjectEffectiveSnapshot,
    val source: ProjectSnapshotSource
)

@JsonPropertyOrder("institutionProjectId", "institutionId", "platformProjectId")
data class ProjectAssociationSnapshot(
    val institutionProjectId: String,
    val institutionId: String,
    val platformProjectId: String
)

@JsonPropertyOrder("name", "category", "description", "tags", "slogan", "detailContent", "coverImage", "images")
data class ProjectRawOverridesSnapshot(
    val name: String?,
    val category: String?,
    val description: String?,
    val tags: List<String>?,
    val slogan: String?,
    val detailContent: String?,
    val coverImage: String?,
    val images: List<String>?
)

@JsonPropertyOrder(
    "name", "category", "description", "tags", "slogan", "detailContent", "salesCount", "coverImage", "images"
)
data class ProjectEffectiveSnapshot(
    val name: String,
    val category: String,
    val description: String?,
    val tags: List<String>,
    val slogan: String?,
    val detailContent: String?,
    val salesCount: Int,
    val coverImage: String?,
    val images: List<String>
)

@JsonPropertyOrder("institutionProjectVersion", "platformInheritanceHash")
data class ProjectSnapshotSource(
    val institutionProjectVersion: Long,
    val platformInheritanceHash: String
)

data class PlatformInheritanceSource(
    val platformProjectId: String,
    val name: String,
    val category: String,
    val description: String?,
    val tags: String?,
    val slogan: String?,
    val detailContent: String?,
    val coverImage: String?,
    val images: String?
)

data class DoctorProjectRevisionSource(
    val institutionProjectId: String,
    val institutionId: String,
    val platformProjectId: String,
    val institutionProjectVersion: Long,
    val platformInheritanceHash: String,
    val doctorProjectUpdatedAt: Instant,
    val configId: String?,
    val configUpdatedAt: Instant?,
    val pricingPolicyRevision: String
)

data class InstitutionProjectDetailResolution(
    val rawOverrides: ProjectRawOverridesSnapshot,
    val effective: ProjectEffectiveSnapshot
)
