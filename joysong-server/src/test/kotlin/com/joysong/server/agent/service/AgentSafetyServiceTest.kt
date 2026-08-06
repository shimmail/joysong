package com.joysong.server.agent.service

import com.joysong.server.agent.dto.SafetyScreeningRequest
import com.joysong.server.agent.entity.AgentSafetyEventEntity
import com.joysong.server.agent.repository.AgentSafetyEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSafetyServiceTest {
    private val repository = mockk<AgentSafetyEventRepository>()
    private val service = AgentSafetyService(repository)

    @Test
    fun `blocks planning when medical risk is present`() {
        every { repository.save(any()) } answers { firstArg<AgentSafetyEventEntity>() }

        val decision = service.evaluate(
            userId = "user-1",
            assessmentId = "assessment-1",
            screening = SafetyScreeningRequest(pregnantOrNursing = true)
        )

        assertEquals("HIGH", decision.level)
        assertEquals("HUMAN_REVIEW", decision.nextAction)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `allows planning when no risk flag is present`() {
        val decision = service.evaluate("user-1", "assessment-1", SafetyScreeningRequest())

        assertEquals("NONE", decision.level)
        assertEquals("PLAN", decision.nextAction)
        verify(exactly = 0) { repository.save(any()) }
    }
}
