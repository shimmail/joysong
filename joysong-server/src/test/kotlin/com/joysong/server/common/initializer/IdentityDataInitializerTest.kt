package com.joysong.server.common.initializer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class IdentityDataInitializerTest {
    @Test
    fun `seeds complete pending project applications and retains actionable profile and split fixtures`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val writes = mutableListOf<JdbcWrite>()
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } answers {
            writes += JdbcWrite(
                firstArg(),
                invocation.args.drop(1).flatMap { argument ->
                    if (argument is Array<*>) argument.asList() else listOf(argument)
                }
            )
            1
        }

        IdentityDataInitializer(jdbcTemplate, jacksonObjectMapper()).run(emptyArray())

        val projectRequests = writes.filter { it.sql.contains("professional_project_requests") }
        assertEquals(2, projectRequests.size)
        assertRequestPayloadsDoNotContainForbiddenFields(projectRequests)
        val platformRequest = projectRequests.single { it.args.first() == PLATFORM_PROJECT_REQUEST_ID }
        val institutionRequest = projectRequests.single { it.args.first() == INSTITUTION_PROJECT_REQUEST_ID }
        assertRequestSqlContainsFields(
            platformRequest,
            listOf("name", "category", "description", "reference_price", "currency", "slogan", "sales_count", "cover_image", "images", "detail_content", "tags", "category_tags", "notes")
        )
        assertRequestSqlContainsFields(
            institutionRequest,
            listOf("project_id", "name", "category", "description", "tags", "slogan", "detail_content", "price", "original_price", "currency", "cover_image", "images", "sales_count", "is_active", "consultation_fee", "commission_rate", "institution_rate", "notes")
        )

        assertEquals(
            listOf(
                PLATFORM_PROJECT_REQUEST_ID,
                SeedIds.DOC_ID_4,
                "热玛吉焕肤疗程",
                "抗衰紧致",
                "面向熟龄肌肤的热玛吉紧致抗衰方案。",
                "[\"热玛吉\",\"紧致抗衰\"]",
                "重塑紧致轮廓",
                "采用分层能量设计，帮助改善松弛与细纹。",
                "CNY",
                "https://via.placeholder.com/800x500?text=ThermageCover",
                "[\"https://via.placeholder.com/800x500?text=Thermage1\",\"https://via.placeholder.com/800x500?text=Thermage2\"]",
                36,
                BigDecimal("9800.00"),
                "[\"抗衰紧致\",\"光电美容\"]",
                "申请创建完整平台项目快照。",
                SeedIds.DOC_ID_4
            ),
            platformRequest.args
        )

        assertEquals(
            listOf(
                INSTITUTION_PROJECT_REQUEST_ID,
                SeedIds.DOC_ID_3,
                SeedIds.INST_ID_2,
                SeedIds.PROJ_ID_1,
                "皮秒焕肤玻尿酸联合方案",
                "注射美容",
                "在机构内提供玻尿酸填充与术后皮肤管理服务。",
                "[\"玻尿酸\",\"术后修护\"]",
                "定制联合美肤方案",
                "由张医生完成面诊、注射与恢复期随访。",
                "CNY",
                "https://via.placeholder.com/800x500?text=BeijingFillerCover",
                "[\"https://via.placeholder.com/800x500?text=BeijingFiller1\",\"https://via.placeholder.com/800x500?text=BeijingFiller2\"]",
                12,
                BigDecimal("3280.00"),
                BigDecimal("3980.00"),
                true,
                BigDecimal("50.00"),
                BigDecimal("10.00"),
                BigDecimal("40.00"),
                "申请加入北京机构的玻尿酸服务目录。",
                SeedIds.DOC_ID_3
            ),
            institutionRequest.args
        )

        val profileUpdate = writes.single { it.sql.contains("doctor_project_change_requests") }
        assertTrue(profileUpdate.sql.contains("current_price"))
        assertEquals(
            listOf(
                SeedIds.PROJECT_CHANGE_REQUEST_ID,
                SeedIds.DOC_ID_2,
                SeedIds.INST_ID_1,
                SeedIds.IP_ID_4,
                "PROFILE_UPDATE",
                "专注眼部年轻化方案，申请更新个人项目介绍。",
                BigDecimal("5299.00"),
                BigDecimal("80.00"),
                BigDecimal("10.00"),
                BigDecimal("40.00"),
                BigDecimal("4999.00"),
                "双眼皮成形的现有个人服务介绍。",
                "[\"双眼皮\",\"眼部整形\"]",
                "每周二、周四下午出诊",
                "https://via.placeholder.com/800x500?text=EyeCurrentCover",
                "[\"https://via.placeholder.com/800x500?text=EyeCurrent1\"]",
                BigDecimal("50.00"),
                BigDecimal("10.00"),
                BigDecimal("40.00"),
                BigDecimal("40.00"),
                BigDecimal("10.00"),
                "更新双眼皮项目资料与分账方案。",
                "[\"双眼皮\",\"面部年轻化\"]",
                "每周二、周四下午出诊",
                "https://via.placeholder.com/800x500?text=EyeProposalCover",
                "[\"https://via.placeholder.com/800x500?text=EyeProposal1\",\"https://via.placeholder.com/800x500?text=EyeProposal2\"]",
                SeedIds.DOC_ID_2
            ),
            profileUpdate.args
        )

        assertTrue(writes.any { it.args.firstOrNull() == SeedIds.SPLIT_CONFIG_ID })
        assertTrue(writes.any { it.args.firstOrNull() == SeedIds.SPLIT_PROPOSAL_ID })
    }

    private fun assertRequestPayloadsDoNotContainForbiddenFields(requests: List<JdbcWrite>) {
        val forbiddenFields = listOf("rating", "review_count", "reviewcount", "doctor_ids", "doctorbindings", "platform_rate", "doctor_rate")
        val requestPayload = requests.joinToString("\n") { "${it.sql}\n${it.args}" }.lowercase()
        forbiddenFields.forEach { forbidden -> assertFalse(requestPayload.contains(forbidden)) }
    }

    private fun assertRequestSqlContainsFields(request: JdbcWrite, fields: List<String>) {
        val sql = request.sql.lowercase()
        fields.forEach { field -> assertTrue(sql.contains(field)) }
    }

    private data class JdbcWrite(val sql: String, val args: List<Any?>)

    private companion object {
        const val PLATFORM_PROJECT_REQUEST_ID = "95000001-0000-4000-8000-000000000004"
        const val INSTITUTION_PROJECT_REQUEST_ID = "95000001-0000-4000-8000-000000000005"
    }
}
