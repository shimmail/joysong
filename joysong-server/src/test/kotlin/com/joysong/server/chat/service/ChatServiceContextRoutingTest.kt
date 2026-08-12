package com.joysong.server.chat.service

import com.joysong.server.agent.service.AgentIntent
import com.joysong.server.agent.service.AgentIntentRouter
import com.joysong.server.agent.service.AgentQueryTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatServiceContextRoutingTest {
    private val router = AgentIntentRouter()

    @Test
    fun `actionless valid summary topic remains available for context completion`() {
        val result = summaryContextDecision("CATALOG_QA:DOCTOR", router)

        assertEquals(AgentIntent.CATALOG_QA, result?.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result?.queryTarget)
    }

    @Test
    fun `summary topic rejects a present mismatched action`() {
        val result = summaryContextDecision("CATALOG_QA:DOCTOR:START_PLANNING", router)

        assertNull(result)
    }
}
