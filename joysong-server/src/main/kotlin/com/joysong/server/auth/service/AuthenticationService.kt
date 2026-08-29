package com.joysong.server.auth.service

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.joysong.server.auth.dto.LoginResponse
import com.joysong.server.auth.dto.UserDto
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.user.service.FixedAdminPhone
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthenticationService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
    private val verificationCodeService: VerificationCodeService,
    @Value("\${google.client-id:}") private val googleClientId: String,
    @Value("\${google.proxy-url:}") private val googleProxyUrl: String,
    @Value("\${admin.bootstrap.phone:}") private val bootstrapPhone: String = "13800000000",
) {
    private val logger = LoggerFactory.getLogger(AuthenticationService::class.java)
    private val dummyPasswordHash = passwordEncoder.encode("joysong-invalid-admin-password")

    private val verifier: GoogleIdTokenVerifier by lazy {
        val transport = if (googleProxyUrl.isNotBlank()) {
            val proxyUri = java.net.URI(googleProxyUrl)
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(proxyUri.host, proxyUri.port)
            )
            NetHttpTransport.Builder()
                .setProxy(proxy)
                .build()
        } else {
            NetHttpTransport()
        }
        GoogleIdTokenVerifier.Builder(transport, GsonFactory.getDefaultInstance())
            .setAudience(listOf(googleClientId))
            .build()
    }

    fun loginWithCode(phone: String, code: String): LoginResponse {
        if (!verificationCodeService.validate(phone, code)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        if (FixedAdminPhone.isReserved(phone, bootstrapPhone)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        val user = userRepository.findByPhone(phone).map { existing ->
            if (existing.role == "ADMIN") {
                throw IllegalArgumentException("验证码无效或已过期")
            }
            when (existing.accountState) {
                AccountState.ACTIVE -> existing
                AccountState.ADMIN_SUSPENDED -> throw IllegalArgumentException("验证码无效或已过期")
                AccountState.ERASED -> newCodeUser(phone)
            }
        }.orElseGet {
            newCodeUser(phone)
        }
        return issueLoginResponse(user)
    }

    fun login(phone: String, password: String): LoginResponse {
        if (FixedAdminPhone.isReserved(phone, bootstrapPhone)) {
            passwordEncoder.matches(password, dummyPasswordHash)
            throw IllegalArgumentException("密码错误，请重试")
        }
        // 手机号未注册则拒绝登录
        val user = userRepository.findByPhone(phone)
            .orElseThrow { IllegalArgumentException("该手机号未注册，请先注册") }
        requireActive(user)
        if (user.role == "ADMIN") {
            passwordEncoder.matches(password, dummyPasswordHash)
            throw IllegalArgumentException("密码错误，请重试")
        }
        // 密码为空说明是验证码注册且未设置密码的用户
        if (user.passwordHash.isEmpty()) {
            throw IllegalArgumentException("您尚未设置密码，请使用验证码登录或通过「忘记密码」重置")
        }
        // 校验密码
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw IllegalArgumentException("密码错误，请重试")
        }
        return issueLoginResponse(user)
    }

    /**
     * 管理后台使用独立认证入口，避免暴露账号是否存在、是否为管理员等信息。
     * 对不存在的账号同样执行一次 BCrypt 校验，降低通过响应耗时枚举账号的风险。
     */
    fun loginAdmin(phone: String, password: String): LoginResponse {
        val configuredPhone = FixedAdminPhone.isReserved(phone, bootstrapPhone) && phone == bootstrapPhone
        val user = if (configuredPhone) userRepository.findByPhone(phone).orElse(null) else null
        val storedPasswordHash = user?.passwordHash?.takeIf { it.isNotBlank() }
        val passwordHash = storedPasswordHash ?: dummyPasswordHash
        val passwordMatches = passwordEncoder.matches(password, passwordHash)

        if (!configuredPhone || user == null || user.accountState != AccountState.ACTIVE || user.role != "ADMIN" || storedPasswordHash == null || !passwordMatches) {
            throw BadCredentialsException("管理员账号或密码错误")
        }

        return issueLoginResponse(user)
    }

    fun register(phone: String, code: String, password: String): LoginResponse {
        if (!verificationCodeService.validate(phone, code)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        if (FixedAdminPhone.isReserved(phone, bootstrapPhone)) {
            throw IllegalArgumentException("手机号已注册")
        }
        val user = userRepository.findByPhone(phone).map { existing ->
            if (existing.role == "ADMIN") {
                throw IllegalArgumentException("手机号已注册")
            }
            when (existing.accountState) {
                AccountState.ERASED -> newPasswordUser(phone, password)
                AccountState.ACTIVE, AccountState.ADMIN_SUSPENDED -> throw IllegalArgumentException("手机号已注册")
            }
        }.orElseGet {
            newPasswordUser(phone, password)
        }
        return issueLoginResponse(user)
    }

    fun loginWithGoogle(idToken: String): LoginResponse {
        // 使用 GoogleIdTokenVerifier 本地验证 JWT 签名、aud、iss、exp
        val googleIdToken: GoogleIdToken = try {
            verifier.verify(idToken)
                ?: throw IllegalArgumentException("Invalid Google ID Token")
        } catch (e: java.security.GeneralSecurityException) {
            logger.warn("Google ID Token verification failed: {}", e.message)
            throw IllegalArgumentException("Invalid Google ID Token")
        } catch (e: java.io.IOException) {
            logger.error("Failed to verify Google ID Token (network/key error): {}", e.message)
            throw IllegalStateException("Google 认证服务暂时不可用，请稍后重试")
        }

        val payload = googleIdToken.payload
        val emailVerified = payload["email_verified"] as? Boolean ?: false
        if (!emailVerified) {
            throw IllegalArgumentException("Google 邮箱未验证，无法登录")
        }
        val email = payload.email ?: throw IllegalArgumentException("无法获取邮箱")
        val name = payload["name"] as? String ?: email.substringBefore("@")
        val picture = payload["picture"] as? String ?: ""

        val user = userRepository.findByEmail(email).map { existing ->
            if (existing.role == "ADMIN") {
                throw IllegalArgumentException("Google 认证失败")
            }
            when (existing.accountState) {
                AccountState.ACTIVE -> existing
                AccountState.ADMIN_SUSPENDED -> throw IllegalArgumentException("Google 认证失败")
                AccountState.ERASED -> newGoogleUser(email, name, picture)
            }
        }.orElseGet {
            newGoogleUser(email, name, picture)
        }

        return issueLoginResponse(user)
    }

    fun refresh(rawRefreshToken: String): LoginResponse {
        val refreshed = refreshTokenService.rotate(rawRefreshToken)
        val user = userRepository.findById(refreshed.userId)
            .orElseThrow { InvalidRefreshTokenException() }
        if (user.accountState != AccountState.ACTIVE) throw InvalidRefreshTokenException()
        return refreshed.tokens.toLoginResponse(user)
    }

    fun logout(rawRefreshToken: String) {
        refreshTokenService.revoke(rawRefreshToken)
    }

    private fun issueLoginResponse(user: UserEntity): LoginResponse {
        requireActive(user)
        return refreshTokenService.issue(user.id, user.phone.orEmpty(), user.role).toLoginResponse(user)
    }

    private fun IssuedTokens.toLoginResponse(user: UserEntity) = LoginResponse(
        token = accessToken,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = accessTokenExpiresIn,
        user = user.toDto()
    )

    private fun UserEntity.toDto() = UserDto(
        id = id, phone = phone, email = email, nickname = nickname,
        avatar = avatar, gender = gender, city = city, bio = bio, birthday = birthday,
        role = role,
        accountState = accountState,
        hasPassword = passwordHash.isNotEmpty()
    )

    private fun requireActive(user: UserEntity) {
        if (user.accountState != AccountState.ACTIVE) {
            throw IllegalArgumentException("账号不可用")
        }
    }

    private fun newCodeUser(phone: String): UserEntity = userRepository.save(
        UserEntity(id = UUID.randomUUID().toString(), phone = phone, passwordHash = "", nickname = phone)
    )

    private fun newPasswordUser(phone: String, password: String): UserEntity = userRepository.save(
        UserEntity(
            id = UUID.randomUUID().toString(),
            phone = phone,
            passwordHash = passwordEncoder.encode(password),
            nickname = phone,
        )
    )

    private fun newGoogleUser(email: String, name: String, picture: String): UserEntity = userRepository.save(
        UserEntity(
            id = UUID.randomUUID().toString(),
            email = email,
            passwordHash = "",
            nickname = name,
            avatar = picture,
        )
    )

    private companion object {
    }
}
