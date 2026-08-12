package com.joysong.server.identity.controller

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
    fun `submit derives actor and exposes normalized consultant response`() {
        val auth = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val service = mockk<InstitutionMembershipRequestService>()
        val actor = ManagementActor("consultant-1", false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet())
        val now = LocalDateTime.of(2026, 8, 12, 10, 0)
        every { access.actor(auth) } returns actor
        every { service.submitConsultant(actor, "institution-1", "join") } returns InstitutionMembershipRequestView(
            "membership-1", MembershipRequestType.CONSULTANT, "consultant-1", "institution-1",
            "PENDING", "join", "", now, now, institutionName = "机构一"
        )

        val response = ConsultantMembershipController(access, service)
            .submit(auth, SubmitConsultantMembershipRequest("institution-1", "join")).data!!

        assertEquals("机构一", response.institutionName)
        assertEquals("PENDING", response.status)
        verify(exactly = 1) { service.submitConsultant(actor, "institution-1", "join") }
    }
}
