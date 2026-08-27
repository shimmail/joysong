package com.joysong.server.user.service

import com.joysong.server.auth.dto.UpdateProfileRequest
import com.joysong.server.auth.dto.UserDto
import com.joysong.server.auth.service.VerificationCodeService
import com.joysong.server.auth.service.VerificationCodePurposeEnum
import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private data class PhoneChangeAuthorization(val expireAt: Instant)

@Service
class UserProfileService(
    private val userRepository: UserRepository,
    private val diaryRepository: DiaryRepository,
    private val passwordEncoder: PasswordEncoder,
    private val verificationCodeService: VerificationCodeService,
    private val refreshTokenService: RefreshTokenService
) {
    private val logger = LoggerFactory.getLogger(UserProfileService::class.java)
    private val phoneChangeAuthorizations = ConcurrentHashMap<String, PhoneChangeAuthorization>()

    companion object {
        private const val PHONE_CHANGE_AUTHORIZATION_SECONDS = 600L
        private val ADMIN_PHONE = Regex("^1[0-9]{10}$")
    }

    fun getUserProfile(userId: String): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        logger.info("getUserProfile - userId: $userId, avatar: ${user.avatar}")
        return user.toDto()
    }

    fun updateProfile(userId: String, request: UpdateProfileRequest): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        logger.info("updateProfile - userId: $userId, request.avatar: ${request.avatar}, current.avatar: ${user.avatar}")
        val updated = user.copy(
            nickname = request.nickname ?: user.nickname,
            avatar = request.avatar ?: user.avatar,
            gender = request.gender ?: user.gender,
            city = request.city ?: user.city,
            bio = request.bio ?: user.bio,
            birthday = request.birthday ?: user.birthday
        )
        userRepository.save(updated)
        logger.info("updateProfile saved - new avatar: ${updated.avatar}")
        return updated.toDto()
    }

    /** 注销账号（逻辑删除） */
    fun deleteAccount(userId: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        logger.info(
            "deleteAccount - userId: {}, phone: {}, email: {}",
            userId,
            user.phone?.let(::maskPhone) ?: "未绑定",
            user.email?.let(::maskEmail) ?: "未绑定"
        )

        // 更新该用户所有日记的作者信息为已注销状态
        val diaries = diaryRepository.findByUserId(userId)
        diaries.forEach { diary ->
            diaryRepository.save(diary.copy(authorName = "已注销用户", authorAvatar = ""))
        }
        logger.info("deleteAccount - updated ${diaries.size} diaries for userId: $userId")

        // @SQLDelete 已配置为逻辑删除：UPDATE users SET deleted_at = NOW() WHERE id = ?
        refreshTokenService.revokeAll(userId)
        userRepository.deleteById(userId)
        logger.info("deleteAccount completed (soft delete) - userId: $userId")
    }

    /** 修改密码 */
    fun changePassword(userId: String, oldPassword: String, newPassword: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("用户不存在") }
        if (user.passwordHash.isEmpty()) {
            throw IllegalArgumentException("您尚未设置密码，请使用「忘记密码」功能设置")
        }
        if (!passwordEncoder.matches(oldPassword, user.passwordHash)) {
            throw IllegalArgumentException("原密码错误")
        }
        validateNewPassword(newPassword, user.role)
        if (passwordEncoder.matches(newPassword, user.passwordHash)) {
            throw IllegalArgumentException("新密码不能与原密码相同")
        }
        val updated = user.copy(
            passwordHash = passwordEncoder.encode(newPassword),
            updatedAt = LocalDateTime.now(),
            credentialsUpdatedAt = LocalDateTime.now()
        )
        userRepository.save(updated)
        refreshTokenService.revokeAll(userId)
        logger.info("changePassword completed - userId: $userId")
    }

    private fun validateNewPassword(password: String, role: String) {
        val minimumLength = if (role == "ADMIN") 12 else 8
        require(password.length in minimumLength..128) { "密码长度应为 $minimumLength-128 位" }
        if (role == "ADMIN") {
            require(password.any(Char::isUpperCase) &&
                password.any(Char::isLowerCase) &&
                password.any(Char::isDigit) &&
                password.any { !it.isLetterOrDigit() }
            ) { "管理员密码必须包含大写字母、小写字母、数字和特殊字符" }
        }
    }

    /** 通过手机号+验证码重置密码 */
    fun resetPassword(phone: String, code: String, newPassword: String) {
        if (!verificationCodeService.validate(phone, code)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        val user = userRepository.findByPhone(phone)
            .orElseThrow { IllegalArgumentException("验证码无效或已过期") }
        if (user.role == "ADMIN") {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        validateNewPassword(newPassword, user.role)
        val updated = user.copy(
            passwordHash = passwordEncoder.encode(newPassword),
            credentialsUpdatedAt = LocalDateTime.now()
        )
        userRepository.save(updated)
        refreshTokenService.revokeAll(user.id)
        logger.info("resetPassword completed - phone: {}", maskPhone(phone))
    }

    /** 已登录用户通过手机号验证码设置密码（适用于未设置密码的用户） */
    fun setPasswordForUser(userId: String, phone: String, code: String, newPassword: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("用户不存在") }
        if (user.passwordHash.isNotEmpty()) {
            throw IllegalArgumentException("您已设置密码，请使用修改密码功能")
        }
        if (user.phone != phone) {
            throw IllegalArgumentException("输入的手机号与账号关联手机号不一致")
        }
        validateNewPassword(newPassword, user.role)
        if (!verificationCodeService.validate(phone, code)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        val updated = user.copy(
            passwordHash = passwordEncoder.encode(newPassword),
            credentialsUpdatedAt = LocalDateTime.now()
        )
        userRepository.save(updated)
        refreshTokenService.revokeAll(userId)
        logger.info("setPasswordForUser completed - userId: $userId")
    }

    /** 向已绑定手机号发送换绑身份确认验证码。 */
    fun sendCurrentPhoneChangeCode(userId: String): Map<String, String> {
        val phone = getBoundPhone(userId)
        verificationCodeService.generate(phone, VerificationCodePurposeEnum.PHONE_CHANGE_CURRENT)
        return mapOf("maskedPhone" to maskPhone(phone), "message" to "验证码已发送")
    }

    /** 第三方登录账号首次绑定手机号，验证码由通用 send-code 接口发送。 */
    fun bindPhone(userId: String, phone: String, code: String) {
        require(phone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) { "手机号格式不正确" }
        val user = userRepository.findById(userId).orElseThrow { IllegalArgumentException("用户不存在") }
        require(user.phone.isNullOrBlank()) { "当前账号已绑定手机号，请使用换绑手机号功能" }
        require(!userRepository.existsByPhone(phone)) { "该手机号已被注册" }
        require(verificationCodeService.validate(phone, code)) { "验证码无效或已过期" }
        userRepository.save(user.copy(phone = phone, credentialsUpdatedAt = LocalDateTime.now()))
        refreshTokenService.revokeAll(userId)
        logger.info("手机号首次绑定完成 - userId={}", userId)
    }

    /** 验证旧手机号验证码，授权本次手机号换绑。 */
    fun verifyCurrentPhoneChangeCode(userId: String, code: String) {
        val phone = getBoundPhone(userId)
        if (!verificationCodeService.validate(phone, code, VerificationCodePurposeEnum.PHONE_CHANGE_CURRENT)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        phoneChangeAuthorizations[userId] = PhoneChangeAuthorization(
            Instant.now().plusSeconds(PHONE_CHANGE_AUTHORIZATION_SECONDS)
        )
    }

    /** 检查新手机号未被占用后，才发送绑定验证码。 */
    fun sendNewPhoneChangeCode(userId: String, phone: String): Map<String, String> {
        require(phone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) { "手机号格式不正确" }
        if (hasBoundPhone(userId)) requirePhoneChangeAuthorization(userId)
        if (getCurrentPhone(userId) == phone) throw IllegalArgumentException("新手机号不能与当前手机号相同")
        if (userRepository.existsByPhone(phone)) throw IllegalArgumentException("该手机号已被注册")
        verificationCodeService.generate(phone, VerificationCodePurposeEnum.PHONE_CHANGE_NEW)
        return mapOf("message" to "验证码已发送")
    }

    /** 校验新手机号验证码并完成换绑。 */
    fun changePhone(userId: String, phone: String, code: String) {
        if (hasBoundPhone(userId)) requirePhoneChangeAuthorization(userId)
        if (getCurrentPhone(userId) == phone) throw IllegalArgumentException("新手机号不能与当前手机号相同")
        if (userRepository.existsByPhone(phone)) throw IllegalArgumentException("该手机号已被注册")
        if (!verificationCodeService.validate(phone, code, VerificationCodePurposeEnum.PHONE_CHANGE_NEW)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        val user = userRepository.findById(userId).orElseThrow { IllegalArgumentException("用户不存在") }
        userRepository.save(user.copy(phone = phone, credentialsUpdatedAt = LocalDateTime.now()))
        refreshTokenService.revokeAll(userId)
        phoneChangeAuthorizations.remove(userId)
        logger.info("手机号换绑完成 - userId={}", userId)
    }

    private fun getBoundPhone(userId: String): String {
        val user = userRepository.findById(userId).orElseThrow { IllegalArgumentException("用户不存在") }
        return user.phone?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("当前账号未绑定手机号")
    }

    private fun hasBoundPhone(userId: String): Boolean {
        val user = userRepository.findById(userId).orElseThrow { IllegalArgumentException("用户不存在") }
        return !user.phone.isNullOrBlank()
    }

    private fun getCurrentPhone(userId: String): String? {
        return userRepository.findById(userId).orElseThrow { IllegalArgumentException("用户不存在") }.phone
    }

    private fun requirePhoneChangeAuthorization(userId: String) {
        val authorization = phoneChangeAuthorizations[userId]
        if (authorization == null || Instant.now().isAfter(authorization.expireAt)) {
            phoneChangeAuthorizations.remove(userId)
            throw IllegalArgumentException("请先完成当前手机号验证")
        }
    }

    private fun maskPhone(phone: String): String = phone.take(5) + "******" + phone.takeLast(2)

    private fun maskEmail(email: String): String {
        val separator = email.indexOf('@')
        if (separator <= 0) return "***"
        val local = email.substring(0, separator)
        return local.take(1) + "***" + email.substring(separator)
    }

    private fun UserEntity.toDto() = UserDto(
        id = id, phone = phone, email = email, nickname = nickname,
        avatar = avatar, gender = gender, city = city, bio = bio, birthday = birthday,
        role = role,
        hasPassword = passwordHash.isNotEmpty()
    )

    // ---- Admin 方法 ----

    fun adminListUsers(keyword: String?): List<UserEntity> {
        return if (keyword.isNullOrBlank()) {
            userRepository.findAllIncludingDeleted()
        } else {
            userRepository.searchUsersIncludingDeleted(keyword.trim())
        }
    }

    fun adminFindById(id: String): UserEntity? = userRepository.findById(id).orElse(null)

    fun adminUpdateRole(id: String, role: String): UserEntity? {
        val user = userRepository.findById(id).orElse(null) ?: return null
        if (role == "ADMIN" && !ADMIN_PHONE.matches(user.phone.orEmpty())) {
            throw IllegalArgumentException("管理员手机号格式不正确")
        }
        val updated = userRepository.save(
            user.copy(role = role, credentialsUpdatedAt = LocalDateTime.now())
        )
        refreshTokenService.revokeAll(id)
        return updated
    }

    fun adminDeactivate(id: String): Pair<Boolean, String> {
        val user = userRepository.findById(id).orElse(null) ?: return false to "用户不存在"
        if (user.deletedAt != null) return false to "该用户已被注销"
        user.deletedAt = LocalDateTime.now()
        userRepository.save(user)
        refreshTokenService.revokeAll(id)
        return true to "success"
    }

    fun adminReactivate(id: String): UserEntity? {
        val user = userRepository.findByIdIncludingDeleted(id) ?: return null
        if (user.role == "ADMIN" && !ADMIN_PHONE.matches(user.phone.orEmpty())) {
            throw IllegalArgumentException("管理员手机号格式不正确")
        }
        return userRepository.save(user.copy(
            deletedAt = null,
            credentialsUpdatedAt = LocalDateTime.now()
        ))
    }

    fun count(): Long = userRepository.countIncludingDeleted()
}
