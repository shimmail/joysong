package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.entity.AccountState
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
    fun updateRole(id: String, role: String): UserEntity? {
        guardRepository.lock()
        val availableAdministrators = requireAvailableAdministrator()
        require(role in PLATFORM_ROLES) { "平台角色只能是 USER 或 ADMIN" }
        val user = userRepository.findByIdForUpdate(id)
            ?.takeIf { it.accountState != AccountState.ERASED }
            ?: return null
        require(user.phone != bootstrapPhone || role == ADMIN_ROLE) {
            "Bootstrap 管理员不能被降权"
        }
        if (role == ADMIN_ROLE) requireAdministratorCredentials(user)
        if (user.isAvailableAdministrator() && role != ADMIN_ROLE) {
            require(availableAdministrators > 1) { "不能降权最后一个可用管理员" }
        }
        if (user.role == role) return user
        val updated = user.copy(role = role, credentialsUpdatedAt = LocalDateTime.now())
        refreshTokenService.revokeAll(id)
        return userRepository.saveAndFlush(updated)
    }

    @Transactional
    fun deactivate(id: String): Pair<Boolean, String> {
        guardRepository.lock()
        val availableAdministrators = requireAvailableAdministrator()
        val user = userRepository.findByIdForUpdate(id)
            ?: return false to "用户不存在"
        if (user.accountState == AccountState.ERASED) return false to "该用户已被注销"
        if (user.accountState == AccountState.ADMIN_SUSPENDED) return false to "该用户已被停用"
        require(user.phone != bootstrapPhone) { "Bootstrap 管理员不能被停用" }
        if (user.isAvailableAdministrator()) {
            require(availableAdministrators > 1) { "不能停用最后一个可用管理员" }
        }
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
        requireAvailableAdministrator()
        val user = userRepository.findByIdForUpdate(id) ?: return null
        if (user.accountState == AccountState.ERASED) {
            throw IllegalArgumentException("注销账号不可恢复")
        }
        if (user.accountState == AccountState.ACTIVE) return user
        if (user.role == ADMIN_ROLE) requireAdministratorCredentials(user)
        val updated = user.copy(
            accountState = AccountState.ACTIVE,
            credentialsUpdatedAt = LocalDateTime.now(),
        )
        refreshTokenService.revokeAll(id)
        return userRepository.saveAndFlush(updated)
    }

    @Transactional
    fun initializeBootstrapAdministrator(phone: String, password: String): UserEntity {
        require(phone == bootstrapPhone && ADMIN_PHONE.matches(phone)) {
            "ADMIN_PHONE must be configured as a valid mobile number"
        }
        require(password.length in 12..128) {
            "ADMIN_PASSWORD must contain 12-128 characters"
        }
        guardRepository.lock()
        val existing = userRepository.findByPhoneForUpdate(phone)
        if (existing != null) {
            check(existing.isAvailableAdministrator()) {
                "The configured ADMIN_PHONE is already used by an unavailable or non-admin account"
            }
            return existing
        }
        val totalUsers = userRepository.countAnyState()
        val availableAdministrators = userRepository.countAvailableAdministrators()
        check(totalUsers == 0L || availableAdministrators > 0L) {
            "现有数据库不存在可用管理员，拒绝自动创建 bootstrap 管理员"
        }
        return userRepository.saveAndFlush(
            UserEntity(
                id = UUID.randomUUID().toString(),
                phone = phone,
                passwordHash = passwordEncoder.encode(password),
                nickname = "系统管理员",
                role = ADMIN_ROLE,
            )
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireOrdinaryAccountDeletionAllowed(userId: String): UserEntity {
        guardRepository.lock()
        requireAvailableAdministrator()
        val user = userRepository.findByIdForUpdate(userId)
            ?.takeIf { it.accountState == AccountState.ACTIVE }
            ?: throw IllegalArgumentException("User not found")
        require(user.phone != bootstrapPhone) { "Bootstrap 管理员不能通过普通用户接口注销" }
        require(user.role != ADMIN_ROLE) { "管理员不能通过普通用户接口注销" }
        return user
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireOrdinaryPhoneChangeAllowed(userId: String, newPhone: String): UserEntity {
        guardRepository.lock()
        val availableAdministrators = requireAvailableAdministrator()
        val user = userRepository.findByIdForUpdate(userId)
            ?.takeIf { it.accountState == AccountState.ACTIVE }
            ?: throw IllegalArgumentException("用户不存在")
        require(user.phone != bootstrapPhone) { "Bootstrap 管理员不能修改引导手机号" }
        if (user.isAvailableAdministrator() && !ADMIN_PHONE.matches(newPhone)) {
            require(availableAdministrators > 1) { "不能使最后一个可用管理员失去登录资格" }
        }
        return user
    }

    private fun requireAvailableAdministrator(): Long =
        userRepository.countAvailableAdministrators().also { count ->
            require(count > 0) { "系统不存在可用管理员，拒绝管理员账号变更" }
        }

    private fun requireAdministratorCredentials(user: UserEntity) {
        require(ADMIN_PHONE.matches(user.phone.orEmpty())) { "管理员手机号格式不正确" }
        require(user.passwordHash.isNotBlank()) { "管理员必须设置可用密码" }
    }

    private fun UserEntity.isAvailableAdministrator(): Boolean =
        role == ADMIN_ROLE &&
            accountState == AccountState.ACTIVE &&
            ADMIN_PHONE.matches(phone.orEmpty()) &&
            passwordHash.isNotBlank()

    private companion object {
        const val ADMIN_ROLE = "ADMIN"
        val PLATFORM_ROLES = setOf("USER", ADMIN_ROLE)
        val ADMIN_PHONE = Regex("^1[0-9]{10}$")
    }
}
