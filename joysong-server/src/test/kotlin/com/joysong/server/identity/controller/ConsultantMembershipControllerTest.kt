package com.joysong.server.identity.controller

import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.InstitutionMembershipRequestQueryService
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.time.LocalDateTime

class ConsultantMembershipControllerTest {
    @Test
    fun `compatibility submit dispatches a fixed consultant join`() {
        val auth = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val service = mockk<InstitutionMembershipRequestService>()
        val queries = mockk<InstitutionMembershipRequestQueryService>()
        val actor = ManagementActor(
            "consultant-1", false, setOf("CONSULTANT"), null,
            emptySet(), emptySet(), emptySet()
        )
        every { access.actor(auth) } returns actor
        every {
            service.submit(
                actor,
                MembershipRequestType.CONSULTANT,
                "institution-1",
                InstitutionMembershipAction.JOIN,
                "join"
            )
        } returns view()

        val response = ConsultantMembershipController(access, service, queries)
            .submit(auth, SubmitConsultantMembershipRequest("institution-1", "join")).data!!

        assertEquals("机构一", response.institutionName)
        assertEquals("PENDING", response.status)
        verify(exactly = 1) {
            service.submit(
                actor,
                MembershipRequestType.CONSULTANT,
                "institution-1",
                InstitutionMembershipAction.JOIN,
                "join"
            )
        }
    }

    private fun view() = InstitutionMembershipRequestView(
        "request-1", MembershipRequestType.CONSULTANT, "consultant-1", "顾问一",
        "institution-1", "机构一", "JOIN", "PENDING", "NONE", "join", "",
        "consultant-1", null, NOW, null, NOW, NOW
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 14, 10, 0)
    }
}
