package com.joysong.server.config

import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class AdminBootstrapInitializer(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${admin.bootstrap.phone:}") private val adminPhone: String,
    @Value("\${admin.bootstrap.password:}") private val adminPassword: String
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(AdminBootstrapInitializer::class.java)

    @Transactional
    override fun run(args: Array<String>) {
        require(adminPhone.matches(Regex("^1\\d{10}$"))) {
            "ADMIN_PHONE must be configured as a valid mobile number"
        }
        require(adminPassword.length in 12..128) {
            "ADMIN_PASSWORD must contain 12-128 characters"
        }

        val existing = userRepository.findByPhoneIncludeDeleted(adminPhone).orElse(null)
        if (existing != null) {
            check(existing.deletedAt == null && existing.role == "ADMIN" && existing.passwordHash.isNotBlank()) {
                "The configured ADMIN_PHONE is already used by an unavailable or non-admin account"
            }
            return
        }

        userRepository.save(
            UserEntity(
                id = UUID.randomUUID().toString(),
                phone = adminPhone,
                passwordHash = passwordEncoder.encode(adminPassword),
                nickname = "系统管理员",
                role = "ADMIN"
            )
        )
        logger.info("Bootstrap administrator account created for the configured phone number")
    }
}
