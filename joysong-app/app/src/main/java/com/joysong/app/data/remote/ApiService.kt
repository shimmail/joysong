package com.joysong.app.data.remote

import com.joysong.app.data.remote.dto.*
import com.joysong.app.domain.model.Settlement
import com.joysong.app.domain.model.UserCoupon
import okhttp3.MultipartBody
import retrofit2.http.*

interface ApiService {

    // AI translation for user-generated and database-backed content
    @POST("api/translations")
    suspend fun translateText(@Body request: TranslateTextRequestDto): ApiResponse<TranslationResponseDto>

    // AI planning agent
    @GET("api/agent/profile")
    suspend fun getAgentProfile(): ApiResponse<AgentProfileDto>

    @PUT("api/agent/profile")
    suspend fun updateAgentProfile(@Body request: AgentProfileRequestDto): ApiResponse<AgentProfileDto>

    @POST("api/agent/profile/confirm")
    suspend fun confirmAgentProfile(): ApiResponse<AgentProfileDto>

    @POST("api/agent/assessments")
    suspend fun createAgentAssessment(
        @Body request: CreateAgentAssessmentRequestDto
    ): ApiResponse<AgentAssessmentDto>

    @POST("api/agent/assessments/{assessmentId}/plans")
    suspend fun createAgentPlan(@Path("assessmentId") assessmentId: String): ApiResponse<AgentPlanDto>

    @GET("api/agent/plans")
    suspend fun getAgentPlans(): ApiResponse<List<AgentPlanDto>>

    @DELETE("api/agent/plans/{planId}")
    suspend fun deleteAgentPlan(@Path("planId") planId: String): ApiResponse<String>

    @DELETE("api/agent/plans")
    suspend fun clearAgentPlans(): ApiResponse<String>

    @POST("api/agent/catalog/report")
    suspend fun createAgentCatalogReport(@Body request: AgentCatalogReportRequestDto): ApiResponse<AgentCatalogReportDto>

    // Auth
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequestDto): ApiResponse<LoginResponseDto>

    @POST("api/auth/login-with-code")
    suspend fun loginWithCode(@Body request: LoginWithCodeRequestDto): ApiResponse<LoginResponseDto>

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequestDto): ApiResponse<LoginResponseDto>

    @POST("api/auth/send-code")
    suspend fun sendCode(@Body request: SendCodeRequestDto): ApiResponse<Map<String, String>>

    @POST("api/auth/login-with-google")
    suspend fun loginWithGoogle(@Body request: GoogleLoginRequestDto): ApiResponse<LoginResponseDto>

    @POST("api/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequestDto): ApiResponse<LoginResponseDto>

    @POST("api/auth/logout")
    suspend fun logout(@Body request: LogoutRequestDto): ApiResponse<Map<String, String>>

    @POST("api/auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequestDto): ApiResponse<Map<String, String>>

    @GET("api/auth/check-phone-registered")
    suspend fun checkPhoneRegistered(@Query("phone") phone: String): ApiResponse<Map<String, Boolean>>

    // User
    @GET("api/user/profile")
    suspend fun getUserProfile(): ApiResponse<UserDto>

    @PUT("api/user/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequestDto): ApiResponse<UserDto>

    @DELETE("api/user/account")
    suspend fun deleteAccount(): ApiResponse<Map<String, String>>

    @POST("api/user/bind-phone")
    suspend fun bindPhone(@Body request: BindPhoneRequestDto): ApiResponse<Map<String, String>>

    @POST("api/user/phone-change/send-current-code")
    suspend fun sendCurrentPhoneChangeCode(): ApiResponse<Map<String, String>>

    @POST("api/user/phone-change/verify-current-code")
    suspend fun verifyCurrentPhoneChangeCode(@Body request: VerifyCodeRequestDto): ApiResponse<Map<String, String>>

    @POST("api/user/phone-change/send-new-code")
    suspend fun sendNewPhoneChangeCode(@Body request: SendCodeRequestDto): ApiResponse<Map<String, String>>

    @PUT("api/user/phone-change")
    suspend fun changePhone(@Body request: ChangePhoneRequestDto): ApiResponse<Map<String, String>>

    @PUT("api/user/password")
    suspend fun changePassword(@Body request: ChangePasswordRequestDto): ApiResponse<Map<String, String>>

    @PUT("api/user/password/set")
    suspend fun setPassword(@Body request: SetPasswordRequestDto): ApiResponse<Map<String, String>>

    // Identity verification
    @GET("api/identity/overview")
    suspend fun getIdentityOverview(): ApiResponse<IdentityOverviewDto>

    @Multipart
    @POST("api/identity/files")
    suspend fun uploadIdentityFile(
        @Part file: MultipartBody.Part,
        @Query("purpose") purpose: String
    ): ApiResponse<PrivateIdentityFileDto>

    @DELETE("api/identity/files/{fileId}")
    suspend fun deleteIdentityFile(@Path("fileId") fileId: String): ApiResponse<Any>

    @POST("api/identity/applications")
    suspend fun submitIdentityApplication(
        @Body request: SubmitIdentityApplicationRequestDto
    ): ApiResponse<IdentityApplicationDto>

    // Home
    @GET("api/home/banners")
    suspend fun getBanners(): ApiResponse<List<BannerDto>>

    @GET("api/home/hot-projects")
    suspend fun getHotProjects(): ApiResponse<List<ProjectDto>>

    @GET("api/home/expert-articles")
    suspend fun getExpertArticles(): ApiResponse<List<ArticleDto>>

    @GET("api/home/user-diaries")
    suspend fun getUserDiaries(): ApiResponse<List<DiaryDto>>

    @GET("api/home/recommended-institution-projects")
    suspend fun getRecommendedInstitutionProjects(): ApiResponse<List<RecommendedInstitutionProjectDto>>

    // Discover
    @GET("api/discover/projects")
    suspend fun getProjects(
        @Query("categories") categories: String = "",
        @Query("cities") cities: String = "",
        @Query("tags") tags: String = "",
        @Query("query") query: String = ""
    ): ApiResponse<List<ProjectWithInstitutionsDto>>

    @GET("api/discover/filter-options")
    suspend fun getFilterOptions(): ApiResponse<FilterOptionsDto>

    @GET("api/discover/diaries")
    suspend fun getDiaries(@Query("query") query: String = ""): ApiResponse<List<DiaryDto>>

    @GET("api/discover/doctors")
    suspend fun getDoctors(@Query("query") query: String = ""): ApiResponse<List<DoctorDto>>

    @GET("api/discover/institutions")
    suspend fun getInstitutions(@Query("query") query: String = ""): ApiResponse<List<InstitutionDto>>

    @GET("api/discover/articles")
    suspend fun getArticles(@Query("query") query: String = ""): ApiResponse<List<ArticleDto>>

    // Discover detail
    @GET("api/discover/projects/{id}")
    suspend fun getProjectById(@Path("id") id: String): ApiResponse<ProjectDetailResponse>

    @GET("api/discover/diaries/{id}")
    suspend fun getDiaryById(@Path("id") id: String): ApiResponse<DiaryDto>

    @GET("api/discover/doctors/{id}")
    suspend fun getDoctorById(@Path("id") id: String): ApiResponse<DoctorDetailResponse>

    @GET("api/discover/institutions/{id}")
    suspend fun getInstitutionById(@Path("id") id: String): ApiResponse<InstitutionDetailResponse>

    @GET("api/discover/institutions/{id}/doctors")
    suspend fun getInstitutionDoctors(@Path("id") id: String): ApiResponse<List<DoctorDto>>

    @GET("api/discover/institutions/{id}/projects")
    suspend fun getInstitutionProjects(@Path("id") id: String): ApiResponse<List<InstitutionProjectWithProjectDto>>

    @GET("api/discover/institutions/{institutionId}/projects/{projectId}")
    suspend fun getInstitutionProjectDetail(
        @Path("institutionId") institutionId: String,
        @Path("projectId") projectId: String
    ): ApiResponse<InstitutionProjectDetailResponse>

    @GET("api/discover/institutions/{id}/diaries")
    suspend fun getInstitutionDiaries(@Path("id") id: String): ApiResponse<List<DiaryDto>>

    @GET("api/discover/articles/{id}")
    suspend fun getArticleById(@Path("id") id: String): ApiResponse<ArticleDto>

    // Institution project doctors
    @GET("api/discover/institution-projects/{institutionProjectId}/doctors")
    suspend fun getInstitutionProjectDoctors(@Path("institutionProjectId") institutionProjectId: String): ApiResponse<List<DoctorDto>>

    // Orders
    @POST("api/orders")
    suspend fun createOrder(@Body request: CreateOrderRequestDto): ApiResponse<OrderDto>

    @GET("api/orders")
    suspend fun getOrders(): ApiResponse<List<OrderDto>>

    @GET("api/orders/{id}")
    suspend fun getOrderById(@Path("id") id: String): ApiResponse<OrderDto>

    @POST("api/orders/{id}/pay")
    suspend fun payOrder(
        @Path("id") id: String,
        @Body request: PayOrderRequestDto
    ): ApiResponse<PaymentDto>

    @POST("api/orders/{id}/refund")
    suspend fun refundOrder(
        @Path("id") id: String,
        @Body request: RefundOrderRequestDto
    ): ApiResponse<RefundDto>

    @POST("api/orders/{id}/review")
    suspend fun reviewOrder(
        @Path("id") id: String,
        @Body request: ReviewOrderRequestDto
    ): ApiResponse<ReviewDto>

    @GET("api/reviews/order/{orderId}")
    suspend fun getReviewByOrder(@Path("orderId") orderId: String): ApiResponse<ReviewDto>

    @PUT("api/reviews/{id}")
    suspend fun updateReview(@Path("id") id: String, @Body request: ReviewOrderRequestDto): ApiResponse<ReviewDto>

    @DELETE("api/reviews/{id}")
    suspend fun deleteReview(@Path("id") id: String): ApiResponse<Map<String, String>>

    @GET("api/orders/{id}/refund")
    suspend fun getRefundDetail(@Path("id") orderId: String): ApiResponse<RefundDto>

    @POST("api/orders/{id}/cancel-refund")
    suspend fun cancelRefund(@Path("id") orderId: String): ApiResponse<String>

    @POST("api/orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") id: String): ApiResponse<String>

    // Order lifecycle
    @POST("api/orders/{id}/pay-consultation")
    suspend fun payConsultationFee(@Path("id") orderId: String): ApiResponse<OrderDto>

    @POST("api/orders/{id}/pay-balance")
    suspend fun payBalance(@Path("id") orderId: String): ApiResponse<OrderDto>

    @POST("api/orders/{id}/verification-code")
    suspend fun verifyOrder(@Path("id") orderId: String): ApiResponse<OrderDto>

    @POST("api/orders/{id}/confirm-completion")
    suspend fun confirmCompletion(@Path("id") orderId: String): ApiResponse<OrderDto>

    @GET("api/orders/{id}/settlement")
    suspend fun getSettlement(@Path("id") orderId: String): ApiResponse<SettlementDto>

    @GET("api/orders/{id}/status-logs")
    suspend fun getStatusLogs(@Path("id") orderId: String): ApiResponse<List<Map<String, Any>>>

    @DELETE("api/orders/{id}")
    suspend fun deleteOrder(@Path("id") id: String): ApiResponse<String>

    // Diaries
    @GET("api/diaries/my")
    suspend fun getMyDiaries(): ApiResponse<List<DiaryDto>>

    @POST("api/diaries")
    suspend fun publishDiary(@Body request: PublishDiaryRequestDto): ApiResponse<DiaryDto>

    @PUT("api/diaries/{id}")
    suspend fun updateDiary(@Path("id") id: String, @Body request: UpdateDiaryRequestDto): ApiResponse<DiaryDto>

    @DELETE("api/diaries/{id}")
    suspend fun deleteDiary(@Path("id") id: String): ApiResponse<String>

    // Likes
    @POST("api/likes")
    suspend fun addLike(@Body request: LikeRequest): ApiResponse<String>

    @DELETE("api/likes/{targetType}/{targetId}")
    suspend fun removeLike(
        @Path("targetType") targetType: String,
        @Path("targetId") targetId: String
    ): ApiResponse<String>

    @GET("api/likes/{targetType}/{targetId}")
    suspend fun checkLike(
        @Path("targetType") targetType: String,
        @Path("targetId") targetId: String
    ): ApiResponse<LikeResponseDto>

    // Comments
    @GET("api/comments")
    suspend fun getComments(@Query("diaryId") diaryId: String): ApiResponse<List<CommentDto>>

    @GET("api/comments/replies")
    suspend fun getCommentReplies(@Query("parentId") parentId: String): ApiResponse<List<CommentDto>>

    @POST("api/comments")
    suspend fun addComment(@Body request: PublishCommentRequest): ApiResponse<CommentDto>

    @DELETE("api/comments/{id}")
    suspend fun deleteComment(@Path("id") id: String): ApiResponse<String>

    // Favorites
    @GET("api/favorites")
    suspend fun getFavorites(): ApiResponse<List<FavoriteDto>>

    @POST("api/favorites")
    suspend fun addFavorite(@Body request: AddFavoriteRequestDto): ApiResponse<Map<String, String>>

    @DELETE("api/favorites/{type}/{targetId}")
    suspend fun removeFavorite(
        @Path("type") type: String,
        @Path("targetId") targetId: String
    ): ApiResponse<Map<String, String>>

    @GET("api/favorites/{type}/{targetId}")
    suspend fun isFavorite(
        @Path("type") type: String,
        @Path("targetId") targetId: String
    ): ApiResponse<FavoriteStatusDto>

    // Chat Sessions
    @POST("api/chat/sessions")
    suspend fun createChatSession(@Body request: CreateChatSessionRequest): ApiResponse<ChatSessionDto>

    @GET("api/chat/sessions")
    suspend fun getChatSessions(@Query("persona") persona: String? = null): ApiResponse<List<ChatSessionDto>>

    @DELETE("api/chat/sessions/{id}")
    suspend fun deleteChatSession(@Path("id") sessionId: String): ApiResponse<String>

    @DELETE("api/chat/sessions")
    suspend fun clearChatSessions(@Query("persona") persona: String = "CONSULTANT"): ApiResponse<String>

    @POST("api/chat/sessions/{id}/messages")
    suspend fun sendChatMessage(
        @Path("id") sessionId: String,
        @Body request: SendChatMessageRequest
    ): ApiResponse<ChatTurnDto>

    @GET("api/chat/sessions/{id}/messages")
    suspend fun getChatMessages(@Path("id") sessionId: String): ApiResponse<List<ChatMessageDto>>

    @DELETE("api/chat/sessions/{id}/messages")
    suspend fun clearChatMessages(@Path("id") sessionId: String): ApiResponse<String>

    // Notifications
    @GET("api/notifications")
    suspend fun getNotifications(): ApiResponse<List<NotificationDto>>

    @PUT("api/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): ApiResponse<Map<String, String>>

    // File Upload
    @Multipart
    @POST("api/upload")
    suspend fun uploadImage(
        @Part file: MultipartBody.Part,
        @Query("folder") folder: String = "general",
        @Query("customFileName") customFileName: String? = null
    ): ApiResponse<UploadResponseDto>

    // User Profile (public, no auth required)
    @GET("api/users/{userId}/profile")
    suspend fun getUserPublicProfile(@Path("userId") userId: String): ApiResponse<UserProfileResponseDto>

    @GET("api/users/{userId}/diaries")
    suspend fun getUserDiariesPublic(@Path("userId") userId: String): ApiResponse<List<DiaryDto>>

    // Reports
    @POST("api/reports")
    suspend fun submitReport(@Body request: ReportRequestDto): ApiResponse<ReportResponseDto>

    @GET("api/reports/check/{targetType}/{targetId}")
    suspend fun checkReported(
        @Path("targetType") targetType: String,
        @Path("targetId") targetId: String
    ): ApiResponse<ReportCheckResponseDto>

    // Coupons
    @GET("api/coupons/available")
    suspend fun getAvailableCoupons(): ApiResponse<List<UserCoupon>>

    @GET("api/coupons/my")
    suspend fun getMyCoupons(@Query("status") status: String? = null): ApiResponse<List<UserCoupon>>

    @GET("api/coupons/{id}/discount")
    suspend fun calculateDiscount(
        @Path("id") couponId: Long,
        @Query("originalPrice") originalPrice: Double
    ): ApiResponse<Map<String, Any>>

    // Consultation fee config
    @GET("api/discover/consultation-fee")
    suspend fun getConsultationFee(
        @Query("doctorId") doctorId: String,
        @Query("institutionProjectId") institutionProjectId: String
    ): ApiResponse<Map<String, Any>>

    // Notifications (supplementary)
    @GET("api/notifications/unread-count")
    suspend fun getUnreadNotificationCount(): ApiResponse<Long>

    @PUT("api/notifications/read-all")
    suspend fun markAllNotificationsRead(): ApiResponse<Map<String, String>>

    // DM Conversations
    @GET("api/dm/conversations")
    suspend fun getDmConversations(): ApiResponse<List<DmConversationDto>>

    @POST("api/dm/conversations")
    suspend fun createDmConversation(@Body request: CreateDmConversationRequest): ApiResponse<DmConversationDto>

    @GET("api/dm/conversations/{id}/messages")
    suspend fun getDmMessages(
        @Path("id") id: String,
        @Query("limit") limit: Int = 30
    ): ApiResponse<List<DmMessageDto>>

    @POST("api/dm/conversations/{id}/messages")
    suspend fun sendDmMessage(
        @Path("id") id: String,
        @Body request: SendDmMessageRequest
    ): ApiResponse<DmMessageDto>

    @PUT("api/dm/conversations/{id}/read")
    suspend fun markDmConversationRead(@Path("id") id: String): ApiResponse<String>

    @DELETE("api/dm/messages/{id}")
    suspend fun deleteDmMessage(@Path("id") id: String): ApiResponse<String>

    @DELETE("api/chat/messages/{id}")
    suspend fun deleteChatMessage(@Path("id") id: String): ApiResponse<String>

    // Customer Service (real human CS)
    @POST("api/cs/conversations")
    suspend fun createCsConversation(): ApiResponse<CsConversationDto>

    @GET("api/cs/conversations")
    suspend fun getCsConversations(): ApiResponse<List<CsConversationDto>>

    @POST("api/cs/conversations/{id}/messages")
    suspend fun sendCsMessage(
        @Path("id") id: String,
        @Body request: SendCsMessageRequest
    ): ApiResponse<CsMessageDto>

    @GET("api/cs/conversations/{id}/messages")
    suspend fun getCsMessages(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50
    ): ApiResponse<List<CsMessageDto>>

    @PUT("api/cs/conversations/{id}/read")
    suspend fun markCsConversationRead(@Path("id") id: String): ApiResponse<String>
}

data class UploadResponseDto(val url: String)
