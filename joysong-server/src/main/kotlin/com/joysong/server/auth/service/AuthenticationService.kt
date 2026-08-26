package com.joysong.server.auth.service

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.joysong.server.auth.dto.LoginResponse
import com.joysong.server.auth.dto.UserDto
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
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
    @Value("\${google.proxy-url:}") private val googleProxyUrl: String
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
        // 查找用户（包括已注销的）
        val user = userRepository.findByPhoneIncludeDeleted(phone).map { existing ->
            if (existing.role == "ADMIN") {
                throw BadCredentialsException("管理员账号请使用密码登录")
            }
            if (existing.deletedAt != null) {
                // 已注销用户重新激活
                val reactivated = existing.copy(deletedAt = null)
                userRepository.save(reactivated)
                reactivated
            } else {
                existing
            }
        }.orElseGet {
            // 全新用户（验证码注册，密码为空，后续可通过"忘记密码"设置）
            val newUser = UserEntity(
                id = UUID.randomUUID().toString(),
                phone = phone,
                passwordHash = "",
                nickname = phone
            )
            userRepository.save(newUser)
        }
        return issueLoginResponse(user)
    }

    fun login(phone: String, password: String): LoginResponse {
        // 手机号未注册则拒绝登录
        val user = userRepository.findByPhone(phone)
            .orElseThrow { IllegalArgumentException("该手机号未注册，请先注册") }
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
        val normalizedPhone = phone.trim()
        val user = userRepository.findByPhone(normalizedPhone).orElse(null)
        val passwordHash = user?.passwordHash?.takeIf { it.isNotBlank() } ?: dummyPasswordHash
        val passwordMatches = passwordEncoder.matches(password, passwordHash)

        if (user == null || user.role != "ADMIN" || !passwordMatches) {
            throw BadCredentialsException("管理员账号或密码错误")
        }

        return issueLoginResponse(user)
    }

    fun register(phone: String, code: String, password: String): LoginResponse {
        if (!verificationCodeService.validate(phone, code)) {
            throw IllegalArgumentException("验证码无效或已过期")
        }
        // 检查是否有未注销的用户（@Where 过滤）
        if (userRepository.findByPhone(phone).isPresent) {
            throw IllegalArgumentException("手机号已注册")
        }
        // 查找是否有已注销的用户，若有则重新激活
        val user = userRepository.findByPhoneIncludeDeleted(phone).map { existing ->
            if (existing.deletedAt != null) {
                // 已注销用户重新激活，更新密码
                val reactivated = existing.copy(
                    deletedAt = null,
                    passwordHash = passwordEncoder.encode(password)
                )
                userRepository.save(reactivated)
                reactivated
            } else {
                existing
            }
        }.orElseGet {
            // 全新用户
            val newUser = UserEntity(
                id = UUID.randomUUID().toString(),
                phone = phone,
                passwordHash = passwordEncoder.encode(password),
                nickname = phone
            )
            userRepository.save(newUser)
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

        // 查找用户（包括已注销的）
        val user = userRepository.findByEmailIncludeDeleted(email).map { existing ->
            if (existing.deletedAt != null) {
                // 已注销用户重新激活
                val reactivated = existing.copy(deletedAt = null)
                userRepository.save(reactivated)
                reactivated
            } else {
                existing
            }
        }.orElseGet {
            // 全新用户（Google 登录，密码为空）
            val newUser = UserEntity(
                id = UUID.randomUUID().toString(),
                email = email,
                passwordHash = "",
                nickname = name,
                avatar = picture
            )
            userRepository.save(newUser)
        }

        return issueLoginResponse(user)
    }

    fun refresh(rawRefreshToken: String): LoginResponse {
        val refreshed = refreshTokenService.rotate(rawRefreshToken)
        val user = userRepository.findById(refreshed.userId)
            .orElseThrow { InvalidRefreshTokenException() }
        return refreshed.tokens.toLoginResponse(user)
    }

    fun logout(rawRefreshToken: String) {
        refreshTokenService.revoke(rawRefreshToken)
    }

    private fun issueLoginResponse(user: UserEntity): LoginResponse =
        refreshTokenService.issue(user.id, user.phone.orEmpty(), user.role).toLoginResponse(user)

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
        hasPassword = passwordHash.isNotEmpty()
    )
}
