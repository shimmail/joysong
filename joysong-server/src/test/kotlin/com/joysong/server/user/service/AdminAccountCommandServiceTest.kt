package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.AdminAccountGuardRepository
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime

class AdminAccountCommandServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val guardRepository = mockk<AdminAccountGuardRepository>(relaxed = true)
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val service = AdminAccountCommandService(
        userRepository,
        guardRepository,
        refreshTokenService,
        passwordEncoder,
        BOOTSTRAP_PHONE
    )

    @Test
    fun `bootstrap administrator cannot be demoted`() {
        val bootstrap = user(id = "bootstrap", phone = BOOTSTRAP_PHONE, role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 2
        every { userRepository.findByIdIncludingDeletedForUpdate(bootstrap.id) } returns bootstrap

        assertThrows(IllegalArgumentException::class.java) {
            service.updateRole(bootstrap.id, "USER")
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `bootstrap administrator cannot be deactivated`() {
        val bootstrap = user(id = "bootstrap", phone = BOOTSTRAP_PHONE, role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 2
        every { userRepository.findByIdIncludingDeletedForUpdate(bootstrap.id) } returns bootstrap

        assertThrows(IllegalArgumentException::class.java) {
            service.deactivate(bootstrap.id)
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `non-bootstrap administrator can be demoted when another available administrator remains`() {
        val admin = user(id = "admin-2", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 2
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        val updated = service.updateRole(admin.id, "USER")

        assertEquals("USER", updated?.role)
        verify(exactly = 1) { refreshTokenService.revokeAll(admin.id) }
    }

    @Test
    fun `non-bootstrap administrator can be deactivated when another available administrator remains`() {
        val admin = user(id = "admin-2", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 2
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        val result = service.deactivate(admin.id)

        assertEquals(true to "success", result)
        verify(exactly = 1) {
            userRepository.saveAndFlush(match { it.id == admin.id && it.deletedAt != null })
        }
        verify(exactly = 1) { refreshTokenService.revokeAll(admin.id) }
    }

    @Test
    fun `last available administrator cannot be demoted`() {
        val admin = user(id = "last-admin", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin

        assertThrows(IllegalArgumentException::class.java) {
            service.updateRole(admin.id, "USER")
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `last available administrator cannot be deactivated`() {
        val admin = user(id = "last-admin", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin

        assertThrows(IllegalArgumentException::class.java) {
            service.deactivate(admin.id)
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `user without a password cannot be promoted to administrator`() {
        val user = user(id = "passwordless-user", passwordHash = "", role = "USER")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(user.id) } returns user

        assertThrows(IllegalArgumentException::class.java) {
            service.updateRole(user.id, "ADMIN")
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `user without a valid administrator phone cannot be promoted`() {
        val user = user(id = "international-user", phone = "+8613900000000", role = "USER")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(user.id) } returns user

        assertThrows(IllegalArgumentException::class.java) {
            service.updateRole(user.id, "ADMIN")
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `valid user can be promoted to administrator`() {
        val user = user(id = "promoted-user", role = "USER")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(user.id) } returns user
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        val updated = service.updateRole(user.id, "ADMIN")

        assertEquals("ADMIN", updated?.role)
        verify(exactly = 1) { refreshTokenService.revokeAll(user.id) }
    }

    @Test
    fun `platform role outside USER and ADMIN is rejected`() {
        val user = user(id = "user", role = "USER")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(user.id) } returns user

        assertThrows(IllegalArgumentException::class.java) {
            service.updateRole(user.id, "DOCTOR")
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `zero available administrator database fails closed`() {
        every { userRepository.countAvailableAdministrators() } returns 0

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.updateRole("any-user", "USER")
        }

        assertEquals("系统不存在可用管理员，拒绝管理员账号变更", error.message)
        verify(exactly = 0) { userRepository.findByIdIncludingDeletedForUpdate(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `refresh token revocation failure prevents administrator role write`() {
        val admin = user(id = "admin-2", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 2
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin
        every { refreshTokenService.revokeAll(admin.id) } throws IllegalStateException("revoke failed")

        assertThrows(IllegalStateException::class.java) {
            service.updateRole(admin.id, "USER")
        }

        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `eligible deactivated administrator can be reactivated with sessions revoked`() {
        val deletedAt = LocalDateTime.now().minusDays(1)
        val admin = user(id = "admin-2", role = "ADMIN", deletedAt = deletedAt)
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        val updated = service.reactivate(admin.id)

        assertNotNull(updated)
        assertNull(updated?.deletedAt)
        verify(exactly = 1) { refreshTokenService.revokeAll(admin.id) }
    }

    @Test
    fun `fresh empty database creates configured bootstrap administrator`() {
        every { userRepository.findByPhoneIncludingDeletedForUpdate(BOOTSTRAP_PHONE) } returns null
        every { userRepository.countIncludingDeleted() } returns 0
        every { userRepository.countAvailableAdministrators() } returns 0
        every { passwordEncoder.encode("StrongPassword1!") } returns "encoded-password"
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        val created = service.initializeBootstrapAdministrator(BOOTSTRAP_PHONE, "StrongPassword1!")

        assertEquals(BOOTSTRAP_PHONE, created.phone)
        assertEquals("ADMIN", created.role)
        assertEquals("encoded-password", created.passwordHash)
        assertNull(created.deletedAt)
    }

    @Test
    fun `populated zero-administrator database is not repaired by bootstrap guessing`() {
        every { userRepository.findByPhoneIncludingDeletedForUpdate(BOOTSTRAP_PHONE) } returns null
        every { userRepository.countIncludingDeleted() } returns 3
        every { userRepository.countAvailableAdministrators() } returns 0

        val error = assertThrows(IllegalStateException::class.java) {
            service.initializeBootstrapAdministrator(BOOTSTRAP_PHONE, "StrongPassword1!")
        }

        assertEquals("现有数据库不存在可用管理员，拒绝自动创建 bootstrap 管理员", error.message)
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `administrator cannot use ordinary account deletion boundary`() {
        val admin = user(id = "admin", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin

        assertThrows(IllegalArgumentException::class.java) {
            service.requireOrdinaryAccountDeletionAllowed(admin.id)
        }
    }

    @Test
    fun `ordinary user remains eligible for account deletion`() {
        val user = user(id = "user", phone = "+8613900000000", role = "USER")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(user.id) } returns user

        assertSame(user, service.requireOrdinaryAccountDeletionAllowed(user.id))
    }

    @Test
    fun `bootstrap administrator cannot change configured phone through ordinary boundary`() {
        val bootstrap = user(id = "bootstrap", phone = BOOTSTRAP_PHONE, role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(bootstrap.id) } returns bootstrap

        assertThrows(IllegalArgumentException::class.java) {
            service.requireOrdinaryPhoneChangeAllowed(bootstrap.id, "+8613900000000")
        }
    }

    @Test
    fun `last available administrator cannot change to a non-admin login phone`() {
        val admin = user(id = "last-admin", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin

        assertThrows(IllegalArgumentException::class.java) {
            service.requireOrdinaryPhoneChangeAllowed(admin.id, "+8613900000000")
        }
    }

    @Test
    fun `unavailable international administrator can still be demoted`() {
        val admin = user(id = "legacy-admin", phone = "+8613900000000", role = "ADMIN")
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        assertEquals("USER", service.updateRole(admin.id, "USER")?.role)
    }

    @Test
    fun `international administrator cannot be reactivated`() {
        val admin = user(
            id = "legacy-admin",
            phone = "+8613900000000",
            role = "ADMIN",
            deletedAt = LocalDateTime.now(),
        )
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(admin.id) } returns admin

        assertThrows(IllegalArgumentException::class.java) {
            service.reactivate(admin.id)
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `professional role account remains reactivatable`() {
        val doctor = user(
            id = "doctor",
            phone = "+8613900000000",
            role = "DOCTOR",
            deletedAt = LocalDateTime.now(),
        )
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.findByIdIncludingDeletedForUpdate(doctor.id) } returns doctor
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        assertNull(service.reactivate(doctor.id)?.deletedAt)
    }

    private fun user(
        id: String,
        phone: String = "13900000000",
        passwordHash: String = "password-hash",
        role: String,
        deletedAt: LocalDateTime? = null,
    ) = UserEntity(
        id = id,
        phone = phone,
        passwordHash = passwordHash,
        nickname = "Admin",
        role = role,
        deletedAt = deletedAt,
    )

    private companion object {
        const val BOOTSTRAP_PHONE = "13800000000"
    }
}
