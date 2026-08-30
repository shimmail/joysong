package com.joysong.server.user.controller

import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

class UserProfileControllerTest {
    private val users = mockk<UserRepository>()
    private val diaries = mockk<DiaryRepository>()
    private val controller = UserProfileController(users, diaries)

    @Test
    fun `suspended user public profile and diaries are not found`() {
        every { users.findById("suspended") } returns Optional.of(
            user(id = "suspended", accountState = AccountState.ADMIN_SUSPENDED)
        )

        assertEquals(404, controller.getUserProfile("suspended").code)
        assertEquals(404, controller.getUserDiaries("suspended").code)

        verify(exactly = 0) { diaries.findByUserId(any()) }
        verify(exactly = 0) { diaries.findPublishedByUserId(any()) }
    }

    @Test
    fun `soft deleted user public profile and diaries are not found`() {
        every { users.findById("deleted") } returns Optional.of(
            user(id = "deleted", deletedAt = LocalDateTime.now())
        )

        assertEquals(404, controller.getUserProfile("deleted").code)
        assertEquals(404, controller.getUserDiaries("deleted").code)

        verify(exactly = 0) { diaries.findByUserId(any()) }
        verify(exactly = 0) { diaries.findPublishedByUserId(any()) }
    }

    @Test
    fun `active user public profile and diaries retain current behavior`() {
        every { users.findById("active") } returns Optional.of(user(id = "active"))
        every { diaries.findByUserId("active") } returns emptyList()
        every { diaries.findPublishedByUserId("active") } returns emptyList()

        assertEquals(200, controller.getUserProfile("active").code)
        assertEquals(200, controller.getUserDiaries("active").code)
    }

    private fun user(
        id: String,
        accountState: AccountState = AccountState.ACTIVE,
        deletedAt: LocalDateTime? = null,
    ) = UserEntity(
        id = id,
        phone = "+8613900000000",
        passwordHash = "test-hash",
        nickname = "公开用户",
        accountState = accountState,
        deletedAt = deletedAt,
    )
}
