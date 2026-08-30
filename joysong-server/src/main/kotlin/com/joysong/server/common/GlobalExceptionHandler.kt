package com.joysong.server.common

import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.security.access.AccessDeniedException
import com.joysong.server.auth.service.InvalidRefreshTokenException
import com.joysong.server.doctor.service.DoctorProfileNotFoundException
import com.joysong.server.institution.service.ManagedInstitutionProfileNotFoundException
import com.joysong.server.identity.service.ConsultantInstitutionRequestConflictException
import com.joysong.server.identity.service.ConsultantInstitutionRequestNotFoundException
import com.joysong.server.identity.service.DoctorInstitutionRequestConflictException
import com.joysong.server.identity.service.DoctorInstitutionRequestNotFoundException
import com.joysong.server.identity.service.InstitutionMembershipRequestConflictException
import com.joysong.server.identity.service.InstitutionMembershipRequestNotFoundException
import com.joysong.server.institution.service.DoctorProjectChangeConflictException
import com.joysong.server.institution.service.DoctorProjectChangeNotFoundException
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.article.service.ArticleNotFoundException
import com.joysong.server.order.service.OrderManagementConflictException
import com.joysong.server.order.service.OrderManagementNotFoundException
import com.joysong.server.order.service.OrderContractException
import com.joysong.server.project.service.ProfessionalProjectRequestConflictException
import com.joysong.server.project.service.ProfessionalProjectRequestNotFoundException
import com.joysong.server.legal.service.LegalDocumentConflictException
import com.joysong.server.legal.service.LegalDocumentNotFoundException
import java.io.IOException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import com.joysong.server.user.deletion.AccountDeletionException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AccountDeletionException::class)
    fun handleAccountDeletion(e: AccountDeletionException): ResponseEntity<BaseResponse<Any>> {
        val code = e.errorCode.status.value()
        val data = e.blockers.takeIf { it.isNotEmpty() }?.let { mapOf("blockers" to it) }
        return ResponseEntity.status(e.errorCode.status).body(
            BaseResponse(
                code = code,
                message = e.errorCode.publicMessage,
                errorCode = e.errorCode.wireCode,
                data = data,
            ),
        )
    }

    @ExceptionHandler(ProjectChangeContractException::class)
    fun handleProjectChangeContract(e: ProjectChangeContractException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(e.status)
            .body(BaseResponse.error(e.message ?: "项目变更请求失败", e.status.value(), e.errorCode.name))

    @ExceptionHandler(OrderContractException::class)
    fun handleOrderContract(e: OrderContractException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(e.status)
            .body(BaseResponse.error(e.message ?: "订单请求失败", e.status.value(), e.errorCode.name))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(BaseResponse.error("请求方法不支持", 405))

    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(e: InvalidRefreshTokenException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(BaseResponse.error(e.message ?: "刷新令牌无效或已过期", 401))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(BaseResponse.error(e.message ?: "无权执行此操作", 403))

    @ExceptionHandler(DoctorProfileNotFoundException::class)
    fun handleDoctorProfileNotFound(
        e: DoctorProfileNotFoundException
    ): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(BaseResponse.error(e.message ?: "医生档案不存在", 404))

    @ExceptionHandler(ManagedInstitutionProfileNotFoundException::class)
    fun handleManagedInstitutionProfileNotFound(
        e: ManagedInstitutionProfileNotFoundException
    ): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(BaseResponse.error(e.message ?: "机构档案不存在", 404))

    @ExceptionHandler(
        ConsultantInstitutionRequestNotFoundException::class,
        DoctorInstitutionRequestNotFoundException::class,
        InstitutionMembershipRequestNotFoundException::class
    )
    fun handleInstitutionMembershipNotFound(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.error<Nothing>(e.message ?: "机构不存在", 404))

    @ExceptionHandler(
        ConsultantInstitutionRequestConflictException::class,
        DoctorInstitutionRequestConflictException::class,
        InstitutionMembershipRequestConflictException::class
    )
    fun handleInstitutionMembershipConflict(e: RuntimeException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.error<Nothing>(e.message ?: "机构关系冲突", 409))

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun handleRequestParameter(e: Exception): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error(e.message ?: "请求参数错误", 400))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(
        e: MissingRequestHeaderException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Nothing>> {
        val errorCode = request.requestURI.takeIf { it.startsWith("/api/user/account-deletion") }
            ?.let { com.joysong.server.user.deletion.AccountDeletionErrorCode.REQUEST_INVALID.wireCode }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error("缺少必要请求头", 400, errorCode))
    }

    @ExceptionHandler(DoctorProjectChangeConflictException::class)
    fun handleDoctorProjectChangeConflict(e: DoctorProjectChangeConflictException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.error<Nothing>(e.message ?: "项目申请冲突", 409))

    @ExceptionHandler(DoctorProjectChangeNotFoundException::class)
    fun handleDoctorProjectChangeNotFound(e: DoctorProjectChangeNotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.error<Nothing>(e.message ?: "项目申请不存在", 404))

    @ExceptionHandler(ArticleNotFoundException::class)
    fun handleArticleNotFound(e: ArticleNotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.error<Nothing>(e.message ?: "文章不存在", 404))

    @ExceptionHandler(OrderManagementNotFoundException::class)
    fun handleOrderManagementNotFound(e: OrderManagementNotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.error<Nothing>(e.message ?: "订单不存在", 404))

    @ExceptionHandler(OrderManagementConflictException::class)
    fun handleOrderManagementConflict(e: OrderManagementConflictException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.error<Nothing>(e.message ?: "订单状态冲突", 409))

    @ExceptionHandler(ProfessionalProjectRequestNotFoundException::class)
    fun handleProfessionalProjectRequestNotFound(e: ProfessionalProjectRequestNotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.error<Nothing>(e.message ?: "项目申请不存在", 404))

    @ExceptionHandler(ProfessionalProjectRequestConflictException::class)
    fun handleProfessionalProjectRequestConflict(e: ProfessionalProjectRequestConflictException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.error<Nothing>(e.message ?: "项目申请冲突", 409))

    @ExceptionHandler(LegalDocumentNotFoundException::class)
    fun handleLegalDocumentNotFound(e: LegalDocumentNotFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.error<Nothing>(e.message ?: "协议不存在", 404))

    @ExceptionHandler(LegalDocumentConflictException::class)
    fun handleLegalDocumentConflict(e: LegalDocumentConflictException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.error<Nothing>(e.message ?: "协议状态冲突", 409))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        e: IllegalArgumentException,
        request: HttpServletRequest
    ): ResponseEntity<BaseResponse<Nothing>> {
        log.warn("参数错误: {}", e.message)
        if (request.requestURI.usesRealHttpErrorStatus()) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.error(e.message ?: "请求参数错误", 400))
        }
        return ResponseEntity.ok(BaseResponse.error<Nothing>(e.message ?: "请求参数错误"))
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleRuntime(
        e: RuntimeException,
        request: HttpServletRequest
    ): ResponseEntity<BaseResponse<Nothing>> {
        log.error("服务器错误", e)
        if (request.requestURI.usesRealHttpErrorStatus()) {
            val errorCode = request.requestURI.takeIf { it.startsWith("/api/user/account-deletion") }
                ?.let { com.joysong.server.user.deletion.AccountDeletionErrorCode.REQUEST_INVALID.wireCode }
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error("服务器内部错误", 500, errorCode))
        }
        return ResponseEntity.ok(BaseResponse.error<Nothing>(e.message ?: "服务器内部错误", 500))
    }

    /**
     * 处理客户端中断连接异常（如用户中途关闭页面、网络断开）
     * 客户端已断开，不再写响应，仅记录 warn 日志
     */
    @ExceptionHandler(ClientAbortException::class)
    fun handleClientAbort(e: ClientAbortException) {
        log.warn("客户端连接中断: {}", e.message)
    }

    /**
     * 处理 IO 异常（Broken pipe / Connection reset 等）
     * 与 ClientAbortException 类似，客户端已断开，不再写响应
     */
    @ExceptionHandler(IOException::class)
    fun handleIOException(e: IOException) {
        val msg = e.message ?: ""
        if (msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("已建立的连接", ignoreCase = true)
        ) {
            log.warn("客户端IO中断: {}", e.message)
        } else {
            log.error("IO异常: {}", e.message, e)
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(
        e: Exception,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<BaseResponse<Nothing>>? {
        // 判断是否为图片资源请求
        val requestUri = request.requestURI
        val contentType = response.contentType ?: ""
        val acceptHeader = request.getHeader("Accept") ?: ""
        val isImageRequest = requestUri.startsWith("/images/") ||
            contentType.startsWith("image/") ||
            acceptHeader.contains("image/")

        // 如果 response 已 committed（如正在写图片流），不再尝试写JSON响应
        if (response.isCommitted) {
            log.warn("响应已committed，跳过异常处理: {}", e.message)
            return null
        }

        if (isImageRequest) {
            // 图片请求异常：仅记录 warn 日志，不返回 JSON 响应（避免 Content-Type 冲突）
            log.warn("图片请求异常 (uri={}): {}", requestUri, e.message)
            return null
        }

        log.error("未知错误", e)
        val body = BaseResponse.error<Nothing>("服务器异常，请稍后重试", 500)
        return if (requestUri.usesRealHttpErrorStatus()) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
        } else {
            ResponseEntity.ok(body)
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        e: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Nothing>> {
        val message = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "请求参数不正确"
        val errorCode = request.requestURI.takeIf { it.startsWith("/api/user/account-deletion") }
            ?.let { com.joysong.server.user.deletion.AccountDeletionErrorCode.REQUEST_INVALID.wireCode }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error(message, 400, errorCode))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(request: HttpServletRequest): ResponseEntity<BaseResponse<Nothing>> {
        val errorCode = request.requestURI.takeIf { it.startsWith("/api/user/account-deletion") }
            ?.let { com.joysong.server.user.deletion.AccountDeletionErrorCode.REQUEST_INVALID.wireCode }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error("请求内容格式不正确", 400, errorCode))
    }

    private fun String.usesRealHttpErrorStatus(): Boolean =
        startsWith("/api/admin/") ||
            startsWith("/api/user/account-deletion") ||
            this == "/api/auth/account" ||
            this == "/api/user/account" ||
            startsWith("/api/public/legal-documents/") ||
            startsWith("/legal/") ||
            startsWith("/api/v2/admin/institution-project-requests") ||
            this == "/api/management/doctor-profile" ||
            startsWith("/api/management/institutions")
            || startsWith("/api/management/consultant-memberships")
            || startsWith("/api/management/institution-membership-requests")
            || startsWith("/api/management/institution-membership-candidates")
            || this == "/api/management/projects"
            || startsWith("/api/management/doctor-articles")
            || startsWith("/api/management/orders")
            || this == "/api/management/project-requests"
            || startsWith("/api/management/project-requests/")
}
