package com.joysong.server.common.initializer

import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
@Order(0)
class UserDataInitializer(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${admin.bootstrap.phone:}") private val adminPhone: String,
    @Value("\${admin.bootstrap.password:}") private val adminPassword: String,
    @Value("\${seed.demo.enabled:false}") private val demoEnabled: Boolean,
    @Value("\${seed.demo.password:}") private val demoPassword: String
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(UserDataInitializer::class.java)

    @Transactional
    override fun run(args: Array<String>) {
        if (demoEnabled && !userRepository.existsById(SeedIds.USER_ID_1)) {
            require(demoPassword.length in 12..128) { "DEMO_USER_PASSWORD must contain 12-128 characters" }
            val demoPasswordHash = passwordEncoder.encode(demoPassword)
            // 演示用户只在空库首次启动时创建。
            userRepository.saveAll(listOf(
                UserEntity(id = SeedIds.DOC_ID_1, phone = "13800138101", passwordHash = demoPasswordHash, nickname = "王医生"),
                UserEntity(id = SeedIds.DOC_ID_2, phone = "13800138102", passwordHash = demoPasswordHash, nickname = "李医生"),
                UserEntity(id = SeedIds.DOC_ID_3, phone = "13800138103", passwordHash = demoPasswordHash, nickname = "张医生"),
                UserEntity(id = SeedIds.DOC_ID_4, phone = "13800138104", passwordHash = demoPasswordHash, nickname = "陈医生"),
                UserEntity(id = SeedIds.DOC_ID_5, phone = "13800138105", passwordHash = demoPasswordHash, nickname = "刘医生"),
                UserEntity(id = SeedIds.DOC_ID_6, phone = "13800138106", passwordHash = demoPasswordHash, nickname = "孙医生"),
                UserEntity(id = SeedIds.CONSULTANT_ID, phone = "13800138201", passwordHash = demoPasswordHash, nickname = "安娜咨询师"),
                UserEntity(id = SeedIds.LEGAL_REP_ID, phone = "13800138202", passwordHash = demoPasswordHash, nickname = "周院长"),
                UserEntity(id = SeedIds.CS_USER_ID, phone = "13800138203", passwordHash = demoPasswordHash, nickname = "机构客服小悦"),
                UserEntity(
                    id = SeedIds.USER_ID_1,
                    phone = "13800138001",
                    passwordHash = demoPasswordHash,
                    nickname = "小美",
                    avatar = "https://via.placeholder.com/200x200?text=User1",
                    gender = "女",
                    role = "USER",
                    createdAt = LocalDateTime.of(2026, 6, 1, 10, 0)
                ),
                UserEntity(
                    id = SeedIds.USER_ID_2,
                    phone = "13800138002",
                    passwordHash = demoPasswordHash,
                    nickname = "小红",
                    avatar = "https://via.placeholder.com/200x200?text=User2",
                    gender = "女",
                    role = "USER",
                    createdAt = LocalDateTime.of(2026, 6, 5, 14, 0)
                ),
                UserEntity(
                    id = SeedIds.USER_ID_3,
                    phone = "13800138003",
                    passwordHash = demoPasswordHash,
                    nickname = "小丽",
                    avatar = "https://via.placeholder.com/200x200?text=User3",
                    gender = "女",
                    role = "USER",
                    createdAt = LocalDateTime.of(2026, 6, 10, 9, 0)
                )
            ))
        }

        ensureAdminExists()
    }

    private fun ensureAdminExists() {
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
                id = SeedIds.ADMIN_ID,
                phone = adminPhone,
                passwordHash = passwordEncoder.encode(adminPassword),
                nickname = "系统管理员",
                avatar = "https://via.placeholder.com/200x200?text=Admin",
                gender = "",
                role = "ADMIN",
                createdAt = LocalDateTime.now()
            )
        )
        logger.info("Bootstrap administrator account created for the configured phone number")
    }
}
