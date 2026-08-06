package com.joysong.app.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.joysong.app.domain.model.*
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 将 ISO-8601 日期时间字符串转换为毫秒时间戳
 * 支持格式如 "2026-07-25T14:30:00"
 * @return 毫秒时间戳，如果字符串为空或格式不正确则返回 0
 */
fun String?.toEpochMilli(): Long {
    if (this.isNullOrBlank()) return 0L
    return try {
        LocalDateTime.parse(this).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0L
    }
}

// Base API response wrapper
data class ApiResponse<T>(
    val code: Int = 200,
    val message: String = "success",
    val data: T? = null
)

// Auth DTOs
data class LoginRequestDto(val phone: String, val password: String)
data class LoginWithCodeRequestDto(val phone: String, val code: String)
data class RegisterRequestDto(val phone: String, val code: String, val password: String)
data class SendCodeRequestDto(val phone: String)
data class VerifyCodeRequestDto(val code: String)
data class ChangePhoneRequestDto(val phone: String, val code: String)
data class ResetPasswordRequestDto(val phone: String, val code: String, val newPassword: String)
data class LoginResponseDto(
    /** 兼容字段；新代码优先读取 accessToken。 */
    val token: String,
    val accessToken: String = token,
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0,
    val user: UserDto
)
data class RefreshTokenRequestDto(val refreshToken: String)
data class LogoutRequestDto(val refreshToken: String)
data class GoogleLoginRequestDto(val idToken: String)
data class UserDto(
    val id: String,
    val phone: String?,
    val email: String?,
    val nickname: String,
    val avatar: String,
    val city: String,
    val bio: String,
    val gender: String = "",
    val birthday: String? = null,
    val hasPassword: Boolean = true
) {
    fun toDomain(token: String? = null) = User(
        id = id, phone = phone, email = email, nickname = nickname,
        avatar = avatar, city = city, bio = bio,
        gender = gender, birthday = birthday ?: "", hasPassword = hasPassword, token = token
    )
}

// Identity verification
data class IdentityOverviewDto(
    val roles: List<IdentityRoleDto> = emptyList(),
    val applications: List<IdentityApplicationDto> = emptyList()
)

data class IdentityRoleDto(
    val roleCode: String = "",
    val status: String = "",
    val activatedAt: String? = null,
    val revokedAt: String? = null
)

data class IdentityApplicationDto(
    val id: String = "",
    val roleCode: String = "",
    val status: String = "",
    val reviewNote: String = "",
    val submittedAt: String? = null,
    val reviewedAt: String? = null
)

data class PrivateIdentityFileDto(
    val fileId: String = "",
    val purpose: String = "",
    val originalName: String = "",
    val contentType: String = "",
    val sizeBytes: Long = 0
)

data class IdentityApplicationDocumentDto(
    val fileId: String,
    val documentType: String
)

data class SubmitIdentityApplicationRequestDto(
    val roleCode: String,
    val applicationData: Map<String, String>,
    val documents: List<IdentityApplicationDocumentDto>
)

// Entity DTOs (match backend entity JSON)
data class BannerDto(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String = "",
    val accentColor: String = "#E8A0BF",
    val sortOrder: Int = 0
) {
    fun toDomain() = Banner(id = id, title = title, subtitle = subtitle,
        imageUrl = imageUrl, accentColor = accentColor)
}

data class ProjectDto(
    val id: String,
    val name: String,
    val referencePrice: Double = 0.0,
    val slogan: String = "",
    val detailContent: String? = null,
    val coverImage: String = "",
    val category: String = "",
    val description: String = "",
    val rating: Float = 4.5f,
    val reviewCount: Int = 0,
    val tags: String = "",
    val createdAt: String = "",
    val images: String = "",
    val salesCount: Int = 0,
    val categoryTags: String = ""
) {
    fun toDomain() = Project(
        id = id, name = name, referencePrice = referencePrice,
        slogan = slogan, detailContent = detailContent,
        coverImage = coverImage, category = category, description = description,
        rating = rating, reviewCount = reviewCount,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        images = if (images.isBlank()) emptyList() else images.split(","),
        salesCount = salesCount,
        categoryTags = if (categoryTags.isBlank()) emptyList() else categoryTags.split(",")
    )
}

data class InstitutionProjectDto(
    val id: String = "",
    val institutionId: String = "",
    val projectId: String = "",
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val rating: Float? = null,
    val reviewCount: Int? = null,
    val tags: String? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val images: String = "",
    val salesCount: Int = 0,
    val isActive: Boolean = true,
    val createdAt: String? = null,
    val deletedAt: String? = null
) {
    fun toDomain(baseProject: Project? = null): InstitutionProject {
        val effectiveImages = if (images.isBlank()) baseProject?.images.orEmpty() else images.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return InstitutionProject(
            id = id,
            institutionId = institutionId,
            projectId = projectId,
            name = name?.takeIf { it.isNotBlank() } ?: baseProject?.name.orEmpty(),
            category = category?.takeIf { it.isNotBlank() } ?: baseProject?.category.orEmpty(),
            description = description?.takeIf { it.isNotBlank() } ?: baseProject?.description.orEmpty(),
            rating = rating ?: baseProject?.rating ?: 0f,
            reviewCount = reviewCount ?: baseProject?.reviewCount ?: 0,
            tags = (tags?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() } ?: baseProject?.tags).orEmpty(),
            slogan = slogan?.takeIf { it.isNotBlank() } ?: baseProject?.slogan.orEmpty(),
            detailContent = detailContent?.takeIf { it.isNotBlank() } ?: baseProject?.detailContent,
            price = price,
            originalPrice = originalPrice,
            coverImage = coverImage.ifBlank { baseProject?.coverImage.orEmpty() },
            images = effectiveImages,
            salesCount = salesCount,
            isActive = isActive
        )
    }
}

data class InstitutionProjectWithInstitutionDto(
    val institutionProject: InstitutionProjectDto,
    val institution: InstitutionDto,
    val project: ProjectDto? = null
)

data class InstitutionProjectWithProjectDto(
    val institutionProject: InstitutionProjectDto,
    val project: ProjectDto
)

/**
 * 机构详情页可预约项目 DTO（后端 InstitutionProjectInfo）
 * 合并了机构项目关联价格与项目模板信息
 */
data class InstitutionProjectInfoDto(
    val institutionProjectId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val description: String = "",
    val category: String = "",
    val categoryTags: String = "",
    val salesCount: Int = 0,
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: String = "",
    val slogan: String = "",
    val detailContent: String? = null,
    val images: String = ""
) {
    fun toDomain() = InstitutionProjectInfo(
        institutionProjectId = institutionProjectId,
        projectId = projectId,
        projectName = projectName,
        price = price,
        originalPrice = originalPrice,
        coverImage = coverImage,
        description = description,
        category = category,
        categoryTags = if (categoryTags.isBlank()) emptyList() else categoryTags.split(","),
        salesCount = salesCount,
        rating = rating,
        reviewCount = reviewCount,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        slogan = slogan,
        detailContent = detailContent,
        images = if (images.isBlank()) emptyList() else images.split(",").map { it.trim() }
    )
}

data class InstitutionDto(
    val id: String,
    val name: String,
    val address: String = "",
    val city: String = "",
    val description: String = "",
    val coverImage: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val isVerified: Boolean = false,
    val images: String = "",
    val projectCount: Int = 0,
    val doctorCount: Int = 0,
    val consultationCount: Int = 0,
    val credentials: String = "",
    val credentialImages: String = "",
    val specialties: String = "",
    val establishedYear: Int? = null,
    val certificationTime: String? = null,
    val userCount: Int = 0,
    val tags: String = "",
    val contactPhone: String = "",
    val businessHours: String = "",
    val caseCount: Int = 0
) {
    fun toDomain() = Institution(
        id = id, name = name, address = address, city = city,
        description = description, coverImage = coverImage,
        rating = rating, reviewCount = reviewCount, isVerified = isVerified,
        images = if (images.isBlank()) emptyList() else images.split(","),
        projectCount = projectCount, doctorCount = doctorCount,
        consultationCount = consultationCount, credentials = credentials,
        credentialImages = credentialImages, specialties = specialties,
        establishedYear = establishedYear, certificationTime = certificationTime ?: "",
        userCount = userCount, tags = tags,
        contactPhone = contactPhone, businessHours = businessHours,
        caseCount = caseCount
    )
}

data class DoctorDto(
    val id: String,
    val name: String,
    val title: String = "",
    val bio: String = "",
    val avatar: String = "",
    val institutionId: String = "",
    val institutionName: String = "",
    val rating: Float = 4.5f,
    val reviewCount: Int = 0,
    val specialties: String = "",
    val isVerified: Boolean = false,
    val consultationCount: Int = 0,
    val credentials: String = "",
    val credentialImages: String = "",
    val caseCount: Int = 0,
    val certificationTags: String = ""
) {
    fun toDomain() = Doctor(
        id = id, name = name, title = title, bio = bio, avatar = avatar,
        institutionId = institutionId, institutionName = institutionName,
        rating = rating, reviewCount = reviewCount,
        specialties = if (specialties.isBlank()) emptyList() else specialties.split(","),
        isVerified = isVerified, consultationCount = consultationCount,
        credentials = credentials, credentialImages = credentialImages,
        caseCount = caseCount,
        certificationTags = if (certificationTags.isBlank()) emptyList() else certificationTags.split(",")
    )
}

data class ArticleDto(
    val id: String,
    val title: String,
    val authorName: String = "",
    val summary: String = "",
    val coverImage: String = "",
    val publishDate: String = "",
    val content: String = "",
    val readCount: Int = 0,
    val doctorId: String = ""
) {
    fun toDomain() = ExpertArticle(
        id = id, title = title, authorName = authorName, summary = summary,
        coverImage = coverImage, publishDate = publishDate,
        content = content, readCount = readCount, doctorId = doctorId
    )
}

data class DiaryDto(
    val id: String,
    val title: String,
    val userId: String = "",
    val authorName: String = "",
    val authorAvatar: String = "",
    val content: String = "",
    val coverImage: String = "",
    val images: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val tags: String = "",
    val publishDate: String = "",
    val doctorId: String = "",
    val projectId: String = "",
    val institutionId: String = "",
    val institutionProjectId: String = "",
    val beforeImages: String = "",
    val afterImages: String = "",
    val orderId: String = "",
    val projectName: String = "",
    val doctorName: String = "",
    val institutionName: String = "",
    val rating: Int = 0,
    val status: String = "published",
    val favoriteCount: Int = 0,
    val isLiked: Boolean = false,
    val createdAt: String? = null
) {
    fun toDomain() = Diary(
        id = id, title = title, userId = userId, authorName = authorName, authorAvatar = authorAvatar,
        content = content, coverImage = coverImage,
        images = if (images.isBlank()) emptyList() else images.split(","),
        likeCount = likeCount, commentCount = commentCount,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        publishDate = publishDate.replace("T", " ").take(16), doctorId = doctorId, projectId = projectId,
        institutionId = institutionId, institutionProjectId = institutionProjectId,
        beforeImages = beforeImages, afterImages = afterImages,
        orderId = orderId, projectName = projectName, doctorName = doctorName,
        institutionName = institutionName,
        rating = rating, status = status, favoriteCount = favoriteCount, isLiked = isLiked,
        createdAt = createdAt?.replace("T", " ")?.take(16) ?: ""
    )
}

data class OrderDto(
    val id: String,
    val userId: String = "",
    val projectName: String,
    val institutionName: String = "",
    val coverImage: String = "",
    @SerializedName("amount")
    val price: Double,
    val paidAmount: Double = 0.0,
    val status: String,
    val createdAt: String = "",
    val appointmentTime: String? = null,
    val qrCode: String? = null,
    val projectId: String = "",
    val institutionId: String = "",
    val doctorId: String = "",
    val consultationFee: Double = 0.0,
    val remainingAmount: Double = 0.0,
    val transactionMethod: String = "",
    val userPhone: String = "",
    val paymentTime: String? = null,
    val refundStatus: String = "NONE",
    val refundAmount: Double = 0.0,
    val doctorName: String = "",
    val orderNo: String = "",
    val verifyCode: String? = null,
    val verifiedAt: String? = null,
    @SerializedName(value = "discountAmount", alternate = ["couponDiscount"])
    val couponDiscount: Double = 0.0,
    val completedAt: String? = null,
    val hasReview: Boolean = false
) {
    fun toDomain() = Order(
        id = id, projectName = projectName, institutionName = institutionName,
        coverImage = coverImage, price = price, paidAmount = paidAmount,
        status = try { OrderStatus.valueOf(status) } catch (e: Exception) { OrderStatus.PENDING_PAYMENT },
        createdAt = createdAt.replace("T", " ").take(16), appointmentTime = appointmentTime?.replace("T", " ")?.take(16) ?: "", qrCode = qrCode ?: "",
        projectId = projectId, institutionId = institutionId, doctorId = doctorId,
        consultationFee = consultationFee, remainingAmount = remainingAmount,
        transactionMethod = transactionMethod, userPhone = userPhone,
        paymentTime = paymentTime?.replace("T", " ")?.take(16) ?: "", refundStatus = refundStatus,
        refundAmount = refundAmount, doctorName = doctorName, orderNo = orderNo,
        verifyCode = verifyCode ?: "", verifiedAt = verifiedAt?.replace("T", " ")?.take(16) ?: "",
        couponDiscount = couponDiscount,
        completedAt = completedAt?.replace("T", " ")?.take(16) ?: "",
        hasReview = hasReview
    )
}

data class SettlementDto(
    val id: Long = 0,
    val orderId: String = "",
    val totalAmount: Double = 0.0,
    val platformAmount: Double = 0.0,
    val institutionAmount: Double = 0.0,
    val consultantAmount: Double = 0.0,
    val doctorAmount: Double = 0.0,
    val status: String = "",
    val settledAt: String? = null
) {
    fun toDomain() = Settlement(
        id = id, orderId = orderId, totalAmount = totalAmount,
        platformAmount = platformAmount, institutionAmount = institutionAmount,
        consultantAmount = consultantAmount, doctorAmount = doctorAmount,
        status = status, settledAt = settledAt.toEpochMilli().takeIf { it > 0 }
    )
}

// Order requests
data class CreateOrderRequestDto(
    val projectId: String,
    val institutionProjectId: String? = null,
    val doctorId: String = "",
    val quantity: Int = 1,
    val remark: String = "",
    val userCouponId: Long? = null,
    val appointmentTime: String? = null
)

// User requests
data class UpdateProfileRequestDto(
    val nickname: String,
    val avatar: String,
    val city: String,
    val bio: String,
    val gender: String? = null,
    val birthday: String? = null
)

data class BindPhoneRequestDto(val phone: String, val code: String)
data class ChangePasswordRequestDto(val oldPassword: String, val newPassword: String)
data class SetPasswordRequestDto(val phone: String, val code: String, val newPassword: String)

// Order action requests
data class PayOrderRequestDto(val method: String)
data class RefundOrderRequestDto(val reason: String, val description: String = "", val evidenceUrl: String = "")
data class ReviewOrderRequestDto(
    val rating: Int,
    val content: String,
    val tags: String = "",
    val images: String = "",
    val targetId: String = "",
    val targetType: String = "",
    val doctorId: String = ""
)

// Payment DTO
data class PaymentDto(
    val id: String,
    val orderId: String,
    val amount: Double,
    val method: String,
    val status: String,
    val paidAt: String = "",
    val transactionId: String = ""
) {
    fun toDomain() = Payment(
        id = id, orderId = orderId, amount = amount, method = method,
        status = try { PaymentStatus.valueOf(status) } catch (e: Exception) { PaymentStatus.PENDING },
        paidAt = paidAt.toEpochMilli(), transactionId = transactionId
    )
}

// Refund DTO
data class RefundDto(
    val id: String,
    val orderId: String,
    val userId: String = "",
    val amount: Double,
    val reason: String,
    val description: String = "",
    val status: String,
    val evidenceUrl: String = "",
    val createdAt: String = "",
    val processedAt: String? = null
) {
    fun toDomain() = Refund(
        id = id, orderId = orderId, amount = amount, reason = reason,
        description = description,
        status = try { RefundStatus.valueOf(status) } catch (e: Exception) { RefundStatus.PENDING },
        createdAt = createdAt.replace("T", " ").take(16), processedAt = processedAt.toEpochMilli()
    )
}

// Review DTO
data class ReviewDto(
    val id: String,
    val orderId: String,
    val userId: String,
    val userName: String = "",
    val doctorId: String = "",
    val rating: Int,
    val content: String,
    val tags: String = "",
    val images: String = "",
    val createdAt: String = ""
) {
    fun toDomain() = Review(
        id = id, orderId = orderId, userId = userId, userName = userName, doctorId = doctorId,
        rating = rating, content = content,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        images = if (images.isBlank()) emptyList() else images.split(","),
        createdAt = createdAt.replace("T", " ").take(16)
    )
}

// Diary request
data class PublishDiaryRequestDto(
    val title: String,
    val content: String,
    val images: String = "",
    val tags: String = "",
    val rating: Int? = null,
    val doctorId: String? = null,
    val projectId: String? = null,
    val institutionId: String? = null,
    val institutionProjectId: String? = null,
    val orderId: String? = null,
    val beforeImages: String? = null,
    val afterImages: String? = null,
    val status: String = "published"
)

// Diary update request
data class UpdateDiaryRequestDto(
    val title: String? = null,
    val content: String? = null,
    val images: String? = null,
    val tags: String? = null,
    val rating: Int? = null,
    val doctorId: String? = null,
    val projectId: String? = null,
    val institutionId: String? = null,
    val institutionProjectId: String? = null,
    val orderId: String? = null,
    val beforeImages: String? = null,
    val afterImages: String? = null,
    val status: String? = null
)

// Comment DTOs
data class CommentDto(
    val id: String = "",
    val diaryId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userAvatar: String = "",
    val content: String = "",
    val parentId: String? = null,
    val replyToUserId: String? = null,
    val replyToUserName: String? = null,
    val createdAt: String = "",
    val likeCount: Int = 0,
    val isLiked: Boolean = false
) {
    fun toDomain() = Comment(
        id = id, diaryId = diaryId, userId = userId,
        userName = userName, userAvatar = userAvatar, content = content,
        parentId = parentId, replyToUserId = replyToUserId,
        replyToUserName = replyToUserName, createdAt = createdAt.replace("T", " ").take(16),
        likeCount = likeCount, isLiked = isLiked
    )
}

data class PublishCommentRequest(
    val diaryId: String = "",
    val content: String = "",
    val parentId: String? = null,
    val replyToUserId: String? = null
)

// Like DTOs
data class LikeRequest(val targetType: String = "", val targetId: String = "")
data class LikeResponseDto(val liked: Boolean = false, val count: Long = 0)

// Favorite DTOs
data class FavoriteDto(
    val id: String,
    val userId: String,
    val targetType: String,
    val targetId: String,
    val targetName: String = "",
    val targetImage: String = "",
    val createdAt: String = ""
) {
    fun toDomain() = Favorite(
        id = id, userId = userId,
        targetType = try { FavoriteType.valueOf(targetType) } catch (e: Exception) { FavoriteType.PROJECT },
        targetId = targetId, targetName = targetName, targetImage = targetImage,
        createdAt = createdAt.replace("T", " ").take(16)
    )
}

data class AddFavoriteRequestDto(
    val targetType: String,
    val targetId: String,
    val targetName: String = "",
    val targetImage: String = ""
)

data class FavoriteStatusDto(
    val favorited: Boolean = false,
    val count: Long = 0
)

// Chat Session DTOs
data class CreateChatSessionRequest(
    val persona: String = "BESTIE",
    val contextType: String = "GENERAL",
    val contextId: String = "",
    val title: String = ""
)

data class SendChatMessageRequest(
    val content: String
)

data class ChatSessionDto(
    val id: String = "",
    val persona: String = "",
    val contextType: String = "",
    val contextId: String = "",
    val title: String = "",
    val lastMessage: String = "",
    val createdAt: String = "",
    val updatedAt: String = ""
)

data class ChatMessageDto(
    val id: String = "",
    val sessionId: String = "",
    val role: String = "",
    val content: String = "",
    val createdAt: String = ""
)

data class ChatTurnDto(
    val message: ChatMessageDto = ChatMessageDto(),
    val catalogReport: AgentCatalogReportDto? = null,
    val catalogItems: List<AgentCatalogItemDto> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE"
)

// Notification DTO
data class NotificationDto(
    val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val type: String = "",
    val isRead: Boolean = false,
    val targetId: String = "",
    val targetType: String = "",
    val createdAt: String = ""
) {
    fun toDomain() = Notification(
        id = id, userId = userId, title = title, content = content,
        type = type, isRead = isRead, targetId = targetId, createdAt = createdAt.replace("T", " ").take(16)
    )
}

// Doctor institution project info (returned in DoctorDetailResponse)
data class DoctorInstitutionProjectInfoDto(
    val institutionProjectId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val institutionId: String = "",
    val institutionName: String = "",
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val salesCount: Int = 0,
    val category: String = "",
    val description: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: String = "",
    val slogan: String = "",
    val detailContent: String? = null
) {
    fun toDomain() = DoctorInstitutionProjectInfo(
        institutionProjectId = institutionProjectId,
        projectId = projectId,
        projectName = projectName,
        institutionId = institutionId,
        institutionName = institutionName,
        price = price,
        originalPrice = originalPrice,
        coverImage = coverImage,
        salesCount = salesCount,
        category = category,
        description = description,
        rating = rating,
        reviewCount = reviewCount,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        slogan = slogan,
        detailContent = detailContent
    )
}

// Detail aggregate responses
data class DoctorDetailResponse(
    val doctor: DoctorDto,
    val institutionProjects: List<DoctorInstitutionProjectInfoDto> = emptyList(),
    val diaries: List<DiaryDto> = emptyList(),
    val institution: InstitutionDto? = null,
    /** 全部有效出诊机构，主机构排在首位。 */
    val institutions: List<InstitutionDto> = emptyList()
)

data class ProjectDetailResponse(
    val project: ProjectDto,
    val institutionProjects: List<InstitutionProjectWithInstitutionDto> = emptyList(),
    val diaries: List<DiaryDto> = emptyList()
)

data class InstitutionProjectDetailResponse(
    val institutionProject: InstitutionProjectDto,
    val project: ProjectDto,
    val institution: InstitutionDto,
    val diaries: List<DiaryDto> = emptyList()
)

data class InstitutionDetailResponse(
    val institution: InstitutionDto,
    val projects: List<InstitutionProjectInfoDto> = emptyList(),
    val doctors: List<DoctorDto> = emptyList(),
    val diaries: List<DiaryDto> = emptyList(),
    val reviews: List<ReviewDto> = emptyList()
)

// 首页推荐机构项目 DTO
data class RecommendedInstitutionProjectDto(
    val institutionProjectId: String = "",
    val institutionId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val institutionName: String = "",
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val category: String = "",
    val salesCount: Int = 0,
    val description: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: String = "",
    val slogan: String = "",
    val detailContent: String? = null
) {
    fun toDomain(): RecommendedInstitutionProject = RecommendedInstitutionProject(
        institutionProjectId = institutionProjectId,
        institutionId = institutionId,
        projectId = projectId,
        projectName = projectName,
        institutionName = institutionName,
        price = price,
        originalPrice = originalPrice,
        coverImage = coverImage,
        category = category,
        salesCount = salesCount,
        description = description,
        rating = rating,
        reviewCount = reviewCount,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        slogan = slogan,
        detailContent = detailContent
    )
}

// Filter options DTO
data class FilterOptionsDto(
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val cities: List<String> = emptyList()
) {
    fun toDomain() = FilterOptions(
        categories = categories,
        tags = tags,
        cities = cities
    )
}

// Institution project item within a project list response
data class InstitutionProjectItemDto(
    val id: String = "",
    val institutionId: String = "",
    val institutionName: String = "",
    val institutionCity: String = "",
    val projectId: String = "",
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: String = "",
    val slogan: String = "",
    val detailContent: String? = null,
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val images: String = "",
    val salesCount: Int = 0,
    val isActive: Boolean = true
) {
    fun toDomain() = InstitutionProjectItem(
        id = id,
        institutionId = institutionId,
        institutionName = institutionName,
        institutionCity = institutionCity,
        projectId = projectId,
        name = name,
        category = category,
        description = description,
        rating = rating,
        reviewCount = reviewCount,
        tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
        slogan = slogan,
        detailContent = detailContent,
        price = price,
        originalPrice = originalPrice,
        coverImage = coverImage,
        images = if (images.isBlank()) emptyList() else images.split(",").map { it.trim() },
        salesCount = salesCount,
        isActive = isActive
    )
}

// Project with nested institution projects (for discover list)
data class ProjectWithInstitutionsDto(
    val id: String = "",
    val name: String = "",
    val referencePrice: Double = 0.0,
    val slogan: String = "",
    val detailContent: String? = null,
    val coverImage: String = "",
    val category: String = "",
    val description: String = "",
    val rating: Float = 4.5f,
    val reviewCount: Int = 0,
    val tags: String = "",
    val createdAt: String = "",
    val images: String = "",
    val salesCount: Int = 0,
    val categoryTags: String = "",
    val institutionProjects: List<InstitutionProjectItemDto> = emptyList()
) {
    fun toDomain() = ProjectWithInstitutions(
        project = Project(
            id = id, name = name, referencePrice = referencePrice,
            slogan = slogan, detailContent = detailContent,
            coverImage = coverImage, category = category, description = description,
            rating = rating, reviewCount = reviewCount,
            tags = if (tags.isBlank()) emptyList() else tags.split(","),
            images = if (images.isBlank()) emptyList() else images.split(","),
            salesCount = salesCount,
            categoryTags = if (categoryTags.isBlank()) emptyList() else categoryTags.split(",")
        ),
        institutionProjects = institutionProjects.map { it.toDomain() }
    )
}

// Report DTOs
data class ReportRequestDto(
    val targetType: String = "",
    val targetId: String = "",
    val reason: String = "",
    val description: String? = null
)

data class ReportResponseDto(
    val id: String = "",
    val userId: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val reason: String = "",
    val description: String? = null,
    val status: String = "",
    val createdAt: String = ""
)

data class ReportCheckResponseDto(
    val reported: Boolean = false
)

// User Profile (public)
data class UserProfileResponseDto(
    val id: String = "",
    val nickname: String = "",
    val avatar: String? = null,
    val gender: String? = null,
    val bio: String? = null,
    val city: String? = null,
    val birthday: String? = null,
    val diaryCount: Int = 0,
    val followingCount: Int = 0,
    val followerCount: Int = 0
)

// DM Conversation DTO
data class DmConversationDto(
    val id: String = "",
    val userAId: String = "",
    val userBId: String = "",
    val lastMessage: String? = null,
    val lastMessageAt: String? = null,
    val userAUnread: Int = 0,
    val userBUnread: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
)

// DM Message DTO
data class DmMessageDto(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val content: String = "",
    val messageType: String = "TEXT",
    val isRead: Boolean = false,
    val createdAt: String = ""
)

// DM Requests
data class CreateDmConversationRequest(
    val targetId: String
)

data class SendDmMessageRequest(
    val content: String,
    val messageType: String = "TEXT"
)
