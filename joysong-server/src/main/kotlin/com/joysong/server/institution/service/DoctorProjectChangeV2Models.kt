package com.joysong.server.institution.service

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime

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

enum class ProjectChangeDecision {
    APPROVED,
    REJECTED,
    CHANGES_REQUESTED
}

data class DoctorProjectReviewV2Command(
    val decision: ProjectChangeDecision,
    val reviewNote: String,
    val force: Boolean,
    val forceBaseRevision: String?
)

class ProjectChangeContractException(
    val status: HttpStatus,
    val errorCode: ProjectChangeErrorCode,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

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

data class DoctorProjectApprovalAuditState(
    val project: InstitutionProjectSnapshotV2,
    val doctorPrice: BigDecimal,
    val doctorActive: Boolean,
    val travelGroundServiceFee: BigDecimal
)

data class DoctorProjectApprovalAuditSnapshot(
    val beforeVersion: Long,
    val afterVersion: Long,
    val latestBefore: DoctorProjectApprovalAuditState,
    val actualApplied: DoctorProjectApprovalAuditState,
    val force: Boolean,
    val driftedFields: List<String>
)

data class InstitutionProjectDetailResolution(
    val rawOverrides: ProjectRawOverridesSnapshot,
    val effective: ProjectEffectiveSnapshot
)

data class DoctorProjectChangeV2Request(
    val requestType: String,
    val institutionProjectId: String,
    val baseRevision: String,
    val name: String?,
    val category: String?,
    val description: String?,
    val tags: List<String>?,
    val slogan: String?,
    val detailContent: String?,
    val price: BigDecimal,
    val salesCount: Int,
    val doctorActive: Boolean,
    val coverImage: String?,
    val images: List<String>?,
    val notes: String
)

data class DoctorProjectProfileUpdateTargetV2(
    val payloadVersion: Int = 2,
    val institutionProjectId: String,
    val institutionId: String,
    val institutionName: String,
    val platformProjectId: String,
    val platformProjectName: String,
    val doctorId: String,
    val doctorName: String,
    val baseRevision: String,
    val currentProject: InstitutionProjectSnapshotV2,
    val currentDoctorPrice: BigDecimal,
    val currentDoctorActive: Boolean,
    val platformRate: BigDecimal,
    val pricingPolicyRevision: String,
    val travelGroundServiceFee: BigDecimal
)

sealed interface DoctorProjectChangeViewV2 {
    val payloadVersion: Int
    val id: String
}

data class LegacyDoctorProjectChangeViewV2(
    override val payloadVersion: Int = 1,
    override val id: String,
    val doctorId: String,
    val doctorName: String,
    val institutionId: String,
    val institutionName: String,
    val institutionProjectId: String,
    val projectName: String,
    val requestType: String,
    val serviceDescription: String,
    val priceSuggestion: BigDecimal?,
    val notes: String,
    val serviceTags: List<String>,
    val scheduleNote: String,
    val coverImage: String,
    val images: List<String>,
    val consultationFee: BigDecimal?,
    val commissionRate: BigDecimal?,
    val institutionRate: BigDecimal?,
    val medicalListPrice: BigDecimal?,
    val platformRate: BigDecimal?,
    val doctorRate: BigDecimal?,
    val forceProcessed: Boolean,
    val currentPrice: BigDecimal?,
    val currentServiceDescription: String?,
    val currentServiceTags: List<String>?,
    val currentScheduleNote: String?,
    val currentCoverImage: String?,
    val currentImages: List<String>?,
    val currentConsultationFee: BigDecimal?,
    val currentMedicalListPrice: BigDecimal?,
    val currentCommissionRate: BigDecimal?,
    val currentInstitutionRate: BigDecimal?,
    val currentPlatformRate: BigDecimal?,
    val currentDoctorRate: BigDecimal?,
    val status: String,
    val submittedBy: String,
    val reviewedBy: String?,
    val reviewerName: String?,
    val reviewNote: String,
    val submittedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val updatedAt: LocalDateTime
) : DoctorProjectChangeViewV2

data class VersionedDoctorProjectChangeViewV2(
    override val payloadVersion: Int = 2,
    override val id: String,
    val requestType: String,
    val doctorId: String,
    val doctorName: String,
    val institutionId: String,
    val institutionName: String,
    val institutionProjectId: String,
    val institutionProjectName: String,
    val platformProjectId: String,
    val platformProjectName: String,
    val baseRevision: String,
    val currentProject: InstitutionProjectSnapshotV2?,
    val proposedProject: InstitutionProjectSnapshotV2?,
    val latestProject: InstitutionProjectSnapshotV2?,
    val latestRevision: String?,
    val sharedChanged: Boolean,
    val currentDoctorPrice: BigDecimal,
    val proposedDoctorPrice: BigDecimal,
    val latestDoctorPrice: BigDecimal?,
    val currentDoctorActive: Boolean,
    val proposedDoctorActive: Boolean,
    val latestDoctorActive: Boolean?,
    val platformRate: BigDecimal,
    val pricingPolicyRevision: String,
    val travelGroundServiceFee: BigDecimal,
    val requestStatus: String,
    val notes: String,
    val forceProcessed: Boolean,
    val submittedBy: String,
    val submittedAt: Instant,
    val reviewedBy: String?,
    val reviewerName: String?,
    val reviewNote: String?,
    val reviewedAt: Instant?,
    val updatedAt: Instant,
    val snapshotState: String,
    val snapshotError: String?,
    val reviewable: Boolean
) : DoctorProjectChangeViewV2
