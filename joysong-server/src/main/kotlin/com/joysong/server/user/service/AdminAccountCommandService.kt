package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.AdminAccountGuardRepository
import com.joysong.server.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class AdminAccountCommandService(
    private val userRepository: UserRepository,
    private val guardRepository: AdminAccountGuardRepository,
    private val refreshTokenService: RefreshTokenService,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${admin.bootstrap.phone:}") private val bootstrapPhone: String,
) {

    @Transactional
    fun deactivate(id: String): Pair<Boolean, String> {
        guardRepository.lock()
        val user = userRepository.findByIdForUpdate(id) ?: return false to "用户不存在"
        if (user.accountState == AccountState.ERASED) return false to "该用户已被注销"
        if (user.accountState == AccountState.ADMIN_SUSPENDED) return false to "该用户已被停用"
        require(!FixedAdminPhone.isReserved(user.phone, bootstrapPhone)) { "固定管理员不能被停用" }
        val updated = user.copy(
            accountState = AccountState.ADMIN_SUSPENDED,
            credentialsUpdatedAt = LocalDateTime.now(),
        )
        refreshTokenService.revokeAll(id)
        userRepository.saveAndFlush(updated)
        return true to "success"
    }

    @Transactional
    fun reactivate(id: String): UserEntity? {
        guardRepository.lock()
        val user = userRepository.findByIdForUpdate(id) ?: return null
        require(!FixedAdminPhone.isReserved(user.phone, bootstrapPhone)) { "固定管理员不能被恢复" }
        if (user.accountState == AccountState.ERASED) throw IllegalArgumentException("注销账号不可恢复")
        if (user.accountState == AccountState.ACTIVE) return user
        val updated = user.copy(
            accountState = AccountState.ACTIVE,
            credentialsUpdatedAt = LocalDateTime.now(),
        )
        refreshTokenService.revokeAll(id)
        return userRepository.saveAndFlush(updated)
    }

    @Transactional
    fun initializeBootstrapAdministrator(phone: String, password: String): UserEntity {
        FixedAdminPhone.requireConfigured(phone, bootstrapPhone)
        guardRepository.lock()
        if (userRepository.countAnyState() == 0L) {
            require(password.length in 12..128) { "ADMIN_PASSWORD must contain 12-128 characters" }
            return userRepository.saveAndFlush(
                UserEntity(
                    id = UUID.randomUUID().toString(),
                    phone = phone,
                    passwordHash = passwordEncoder.encode(password),
                    nickname = "系统管理员",
                    role = ADMIN_ROLE,
                ),
            )
        }

        val configured = userRepository.findByPhoneForUpdate(phone)
            ?: throw IllegalStateException("Configured administrator is missing or its phone has drifted")
        check(
            configured.role == ADMIN_ROLE &&
                configured.accountState == AccountState.ACTIVE &&
                configured.passwordHash.isNotBlank(),
        ) { "The configured ADMIN_PHONE is already used by an unavailable or non-admin account" }
        check(
            userRepository.countNonErasedPhoneOwnersForUpdate(phone, FixedAdminPhone.e164Alias(phone)) == 1L,
        ) { "The configured ADMIN_PHONE has another non-erased owner" }
        check(userRepository.countNonErasedAdministratorsForUpdate() == 1L) {
            "Only the configured administrator may remain active"
        }
        return configured
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireOrdinaryPhoneChangeAllowed(userId: String, newPhone: String): UserEntity {
        guardRepository.lock()
        val user = userRepository.findByIdForUpdate(userId)
            ?.takeIf { it.accountState == AccountState.ACTIVE }
            ?: throw IllegalArgumentException("用户不存在")
        require(!FixedAdminPhone.isReserved(user.phone, bootstrapPhone)) { "固定管理员不能修改手机号" }
        require(!FixedAdminPhone.isReserved(newPhone, bootstrapPhone)) { "该手机号为系统管理员保留号码" }
        return user
    }

    private companion object {
        const val ADMIN_ROLE = "ADMIN"
    }
}
