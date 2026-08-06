package com.joysong.app.ui.navigation

sealed class Routes(val route: String) {
    data object Login : Routes("login")
    data object Main : Routes("main")
    data object Home : Routes("home")
    data object Discover : Routes("discover?tab={tab}") {
        fun createRoute(tab: Int = 0) = if (tab > 0) "discover?tab=$tab" else "discover"
    }
    data object AiAgent : Routes("ai_agent?contextType={contextType}&contextId={contextId}&contextName={contextName}&role={role}") {
        fun createRoute(
            contextType: String = "",
            contextId: String = "",
            contextName: String = "",
            role: String = ""
        ): String {
            val encodedName = java.net.URLEncoder.encode(contextName, "UTF-8")
            return "ai_agent?contextType=$contextType&contextId=$contextId&contextName=$encodedName&role=$role"
        }
    }
    data object Profile : Routes("profile")
    data object ProjectDetail : Routes("project/{projectId}") {
        fun createRoute(projectId: String) = "project/$projectId"
    }
    data object InstitutionDetail : Routes("institution/{institutionId}") {
        fun createRoute(institutionId: String) = "institution/$institutionId"
    }
    data object DoctorDetail : Routes("doctor/{doctorId}") {
        fun createRoute(doctorId: String) = "doctor/$doctorId"
    }
    data object ArticleDetail : Routes("article/{articleId}") {
        fun createRoute(articleId: String) = "article/$articleId"
    }
    data object DiaryDetail : Routes("diary/{diaryId}") {
        fun createRoute(diaryId: String) = "diary/$diaryId"
    }
    data object OrderList : Routes("orders")
    data object OrderDetail : Routes("orders/{orderId}") {
        fun createRoute(orderId: String) = "orders/$orderId"
    }
    data object Settings : Routes("settings")
    data object EditProfile : Routes("edit_profile")
    data object MyDiaries : Routes("my_diaries")
    data object Favorites : Routes("favorites")
    data object CustomerService : Routes("customer_service")
    data object HelpFeedback : Routes("help_feedback")
    data object AboutUs : Routes("about_us")
    data object AccountSecurity : Routes("account_security")
    data object OfficialVerification : Routes("official_verification")
    data object IdentityApplication : Routes("identity_application/{roleCode}") {
        fun createRoute(roleCode: String) = "identity_application/$roleCode"
    }
    data object ChangePassword : Routes("change_password")
    data object ResetPassword : Routes("reset_password")
    data object BindPhone : Routes("bind_phone")
    data object BindEmail : Routes("bind_email")
    data object PublishDiary : Routes("publish_diary?diaryId={diaryId}") {
        fun createRoute(diaryId: String = "") = "publish_diary?diaryId=$diaryId"
    }
    data object RefundApply : Routes("refund/{orderId}") {
        fun createRoute(orderId: String) = "refund/$orderId"
    }
    data object ReviewOrder : Routes("review/{orderId}?editMode={editMode}") {
        fun createRoute(orderId: String, editMode: Boolean = false): String {
            return "review/$orderId?editMode=$editMode"
        }
    }
    data object Appointment : Routes("appointment/{projectId}/{institutionId}") {
        fun createRoute(projectId: String, institutionId: String) = "appointment/$projectId/$institutionId"
    }
    data object Payment : Routes("payment/{projectId}/{institutionId}/{doctorName}/{appointmentTime}") {
        fun createRoute(projectId: String, institutionId: String, doctorName: String, time: String) =
            "payment/$projectId/$institutionId/${java.net.URLEncoder.encode(doctorName, "UTF-8")}/${java.net.URLEncoder.encode(time, "UTF-8")}"
    }
    data object AppointmentSuccess : Routes("appointment_success/{institutionName}/{doctorName}/{appointmentTime}") {
        fun createRoute(institutionName: String, doctorName: String, time: String) =
            "appointment_success/${java.net.URLEncoder.encode(institutionName, "UTF-8")}/${java.net.URLEncoder.encode(doctorName, "UTF-8")}/${java.net.URLEncoder.encode(time, "UTF-8")}"
    }
    data object SelectDoctor : Routes("select_doctor?institutionProjectId={institutionProjectId}") {
        fun createRoute(institutionProjectId: String = "") = "select_doctor?institutionProjectId=$institutionProjectId"
    }
    data object SelectProject : Routes("select_project")
    data object SelectInstitution : Routes("select_institution")
    data object InstitutionProjectDetail : Routes("institution/{institutionId}/project/{projectId}") {
        fun createRoute(institutionId: String, projectId: String) = "institution/$institutionId/project/$projectId"
    }
    data object ProjectAllDiaries : Routes("project_all_diaries/{projectId}") {
        fun createRoute(projectId: String) = "project_all_diaries/$projectId"
    }
    data object ProjectAllInstitutions : Routes("project_all_institutions/{projectId}") {
        fun createRoute(projectId: String) = "project_all_institutions/$projectId"
    }
    data object DoctorAllDiaries : Routes("doctor_all_diaries/{doctorId}") {
        fun createRoute(doctorId: String) = "doctor_all_diaries/$doctorId"
    }
    data object DoctorAllProjects : Routes("doctor_all_projects/{doctorId}") {
        fun createRoute(doctorId: String) = "doctor_all_projects/$doctorId"
    }
    data object InstitutionAllDiaries : Routes("institution_all_diaries/{institutionId}") {
        fun createRoute(institutionId: String) = "institution_all_diaries/$institutionId"
    }
    data object InstitutionAllProjects : Routes("institution_all_projects/{institutionId}") {
        fun createRoute(institutionId: String) = "institution_all_projects/$institutionId"
    }
    data object InstitutionAllDoctors : Routes("institution_all_doctors/{institutionId}") {
        fun createRoute(institutionId: String) = "institution_all_doctors/$institutionId"
    }
    data object InstitutionAllReviews : Routes("institution_all_reviews/{institutionId}") {
        fun createRoute(institutionId: String) = "institution_all_reviews/$institutionId"
    }
    data object ProjectFilter : Routes("project_filter")
    data object Messages : Routes("messages")
    data object UserProfile : Routes("user_profile/{userId}") {
        fun createRoute(userId: String) = "user_profile/$userId"
    }
    data object DmChat : Routes("dm_chat/{targetId}") {
        fun createRoute(targetId: String, type: String = "user") = "dm_chat/$targetId?type=$type"
    }
    data object CouponList : Routes("coupons?originalPrice={originalPrice}") {
        fun createRoute(originalPrice: Double = 0.0) = "coupons?originalPrice=$originalPrice"
    }
    data object OrderPayment : Routes("order_payment/{orderId}/{paymentType}") {
        fun createRoute(orderId: String, paymentType: String) = "order_payment/$orderId/$paymentType"
    }
    data object BookingConfirm : Routes("booking_confirm/{institutionProjectId}/{projectId}/{institutionId}") {
        fun createRoute(institutionProjectId: String, projectId: String, institutionId: String) =
            "booking_confirm/$institutionProjectId/$projectId/$institutionId"
    }
}
