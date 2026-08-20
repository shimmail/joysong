package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate

class IdentityApplicationServiceTest {

    @Test
    fun `doctor application succeeds without hospital name`() {
        val service = service()

        val result = service.submit("doctor-1", doctorRequest())

        assertEquals("DOCTOR", result.roleCode)
        assertEquals("PENDING", result.status)
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["示例医院"])
    fun `doctor application rejects hospital name whenever the key is present`(hospitalName: String?) {
        val service = service()
        val applicationData = doctorApplicationData() + ("hospitalName" to hospitalName)

        val error = assertThrows<IllegalArgumentException> {
            service.submit("doctor-1", doctorRequest(applicationData))
        }

        assertEquals("医生身份申请不允许填写执业机构", error.message)
    }

    private fun service(): IdentityApplicationService {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg())
        } answers {
            when {
                firstArg<String>().contains("FROM users") -> 1L
                firstArg<String>().contains("FROM private_files") -> 1L
                else -> 0L
            }
        }
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        return IdentityApplicationService(jdbcTemplate, ObjectMapper())
    }

    private fun doctorRequest(
        applicationData: Map<String, Any?> = doctorApplicationData()
    ) = SubmitIdentityApplicationRequest(
        roleCode = "DOCTOR",
        applicationData = applicationData,
        documents = listOf(
            IdentityApplicationDocumentRequest("file-front", "ID_CARD_FRONT"),
            IdentityApplicationDocumentRequest("file-back", "ID_CARD_BACK"),
            IdentityApplicationDocumentRequest("file-handheld", "ID_CARD_HANDHELD"),
            IdentityApplicationDocumentRequest("file-qualification", "DOCTOR_QUALIFICATION"),
            IdentityApplicationDocumentRequest("file-practice", "DOCTOR_PRACTICE_CERTIFICATE")
        )
    )

    private fun doctorApplicationData(): Map<String, Any?> = mapOf(
        "realName" to "张三",
        "idNumber" to "110101199001011234",
        "department" to "皮肤科",
        "title" to "主治医师",
        "qualificationNo" to "QUALIFICATION-1",
        "practiceNo" to "PRACTICE-1",
        "reason" to "申请医生身份"
    )
}
