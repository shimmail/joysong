package com.joysong.server.user.service

import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AccountLifecycleGuardTest {
    private val userRepository = mockk<UserRepository>()
    private val guard = AccountLifecycleGuard(userRepository)

    @Test
    fun `active user is returned while holding its row lock`() {
        val user = user(AccountState.ACTIVE)
        every { userRepository.findByIdForUpdate(user.id) } returns user

        assertEquals(user, guard.requireActiveForWrite(user.id))
    }

    @Test
    fun `non-active user is rejected for writes`() {
        val user = user(AccountState.ERASED)
        every { userRepository.findByIdForUpdate(user.id) } returns user

        assertThrows(IllegalStateException::class.java) {
            guard.requireActiveForWrite(user.id)
        }
    }

    private fun user(state: AccountState) = UserEntity(
        id = "user-id",
        phone = "13900000000",
        passwordHash = "hash",
        accountState = state,
    )
}
