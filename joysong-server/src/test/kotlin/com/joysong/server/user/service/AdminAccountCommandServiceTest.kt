package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.AdminAccountGuardRepository
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder

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
        FIXED_ADMIN_PHONE,
    )

    @Test
    fun `empty users table creates the configured fixed administrator`() {
        every { userRepository.countAnyState() } returns 0
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns null
        every { userRepository.countAvailableAdministrators() } returns 0
        every { passwordEncoder.encode("StrongPassword1!") } returns "encoded-password"
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        val created = service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "StrongPassword1!")

        assertEquals(FIXED_ADMIN_PHONE, created.phone)
        assertEquals("ADMIN", created.role)
        assertEquals(AccountState.ACTIVE, created.accountState)
        assertEquals("encoded-password", created.passwordHash)
    }

    @Test
    fun `valid configured administrator is verified without an admin password or writes`() {
        val configured = administrator(id = "fixed-admin")
        every { userRepository.countAnyState() } returns 1
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns configured
        every { userRepository.findAllAnyState() } returns listOf(configured)

        val verified = service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "")

        assertSame(configured, verified)
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `configured administrator is not rewritten when a password is supplied`() {
        val configured = administrator(
            id = "fixed-admin",
            passwordHash = "existing-hash",
            nickname = "Existing profile",
        )
        every { userRepository.countAnyState() } returns 1
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns configured
        every { userRepository.findAllAnyState() } returns listOf(configured)

        val verified = service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "ReplacementPassword1!")

        assertSame(configured, verified)
        assertEquals("existing-hash", verified.passwordHash)
        assertEquals("Existing profile", verified.nickname)
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `nonempty database without the configured administrator fails without writes`() {
        every { userRepository.countAnyState() } returns 1
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns null
        every { userRepository.countAvailableAdministrators() } returns 0

        assertThrows(IllegalStateException::class.java) {
            service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "StrongPassword1!")
        }

        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `configured phone conflict fails without writes`() {
        val conflictingUser = administrator(role = "USER")
        every { userRepository.countAnyState() } returns 1
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns conflictingUser

        assertThrows(IllegalStateException::class.java) {
            service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "StrongPassword1!")
        }

        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `phone drift of the fixed administrator fails startup without writes`() {
        val driftedAdministrator = administrator(id = "fixed-admin", phone = "+8613800000000")
        every { userRepository.countAnyState() } returns 1
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns null
        every { userRepository.findAllAnyState() } returns listOf(driftedAdministrator)
        every { userRepository.countAvailableAdministrators() } returns 1
        every { passwordEncoder.encode(any()) } returns "unexpected-hash"
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        assertThrows(IllegalStateException::class.java) {
            service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "StrongPassword1!")
        }

        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `a second non-erased administrator fails startup without writes`() {
        val configured = administrator(id = "fixed-admin")
        val secondAdministrator = administrator(id = "second-admin", phone = "13900000000")
        every { userRepository.countAnyState() } returns 2
        every { userRepository.findByPhoneForUpdate(FIXED_ADMIN_PHONE) } returns configured
        every { userRepository.findAllAnyState() } returns listOf(configured, secondAdministrator)

        assertThrows(IllegalStateException::class.java) {
            service.initializeBootstrapAdministrator(FIXED_ADMIN_PHONE, "StrongPassword1!")
        }

        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `fixed administrator cannot be deactivated`() {
        val fixedAdmin = administrator(id = "fixed-admin")
        every { userRepository.findByIdForUpdate(fixedAdmin.id) } returns fixedAdmin
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        assertThrows(IllegalArgumentException::class.java) {
            service.deactivate(fixedAdmin.id)
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `fixed administrator cannot be reactivated`() {
        val fixedAdmin = administrator(id = "fixed-admin", accountState = AccountState.ADMIN_SUSPENDED)
        every { userRepository.findByIdForUpdate(fixedAdmin.id) } returns fixedAdmin
        every { userRepository.countAvailableAdministrators() } returns 1
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }

        assertThrows(IllegalArgumentException::class.java) {
            service.reactivate(fixedAdmin.id)
        }

        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `fixed administrator cannot use the ordinary deletion boundary`() {
        val fixedAdmin = administrator(id = "fixed-admin")
        every { userRepository.findByIdForUpdate(fixedAdmin.id) } returns fixedAdmin
        every { userRepository.countAvailableAdministrators() } returns 1

        assertThrows(IllegalArgumentException::class.java) {
            service.requireOrdinaryAccountDeletionAllowed(fixedAdmin.id)
        }
    }

    @Test
    fun `fixed administrator cannot change phone through the ordinary boundary`() {
        val fixedAdmin = administrator(id = "fixed-admin")
        every { userRepository.findByIdForUpdate(fixedAdmin.id) } returns fixedAdmin
        every { userRepository.countAvailableAdministrators() } returns 1

        assertThrows(IllegalArgumentException::class.java) {
            service.requireOrdinaryPhoneChangeAllowed(fixedAdmin.id, "+8613900000000")
        }
    }

    @Test
    fun `ordinary user cannot change to the E164 alias of the fixed phone`() {
        val user = administrator(id = "user-id", phone = "+8613900000000", role = "USER")
        every { userRepository.findByIdForUpdate(user.id) } returns user

        assertThrows(IllegalArgumentException::class.java) {
            service.requireOrdinaryPhoneChangeAllowed(user.id, "+8613800000000")
        }
    }

    private fun administrator(
        id: String = "admin-id",
        phone: String = FIXED_ADMIN_PHONE,
        passwordHash: String = "password-hash",
        nickname: String = "Admin",
        role: String = "ADMIN",
        accountState: AccountState = AccountState.ACTIVE,
    ) = UserEntity(
        id = id,
        phone = phone,
        passwordHash = passwordHash,
        nickname = nickname,
        role = role,
        accountState = accountState,
    )

    private companion object {
        const val FIXED_ADMIN_PHONE = "13800000000"
    }
}
