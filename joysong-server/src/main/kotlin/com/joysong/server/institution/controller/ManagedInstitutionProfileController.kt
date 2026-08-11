package com.joysong.server.institution.controller

import com.fasterxml.jackson.databind.JsonNode
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.ManagedInstitutionProfile
import com.joysong.server.institution.service.ManagedInstitutionProfileService
import com.joysong.server.institution.service.ManagedInstitutionProfileUpdateCommand
import com.joysong.server.institution.service.ManagedInstitutionSummary
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Year

@RestController
@RequestMapping("/api/management/institutions")
class ManagedInstitutionProfileController(
    private val managedInstitutionProfileService: ManagedInstitutionProfileService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<List<ManagedInstitutionSummary>> {
        val actor = legalRepresentative(authentication)
        return BaseResponse.success(managedInstitutionProfileService.list(actor))
    }

    @GetMapping("/{institutionId}")
    fun get(
        authentication: Authentication,
        @PathVariable institutionId: String
    ): BaseResponse<ManagedInstitutionProfile> {
        val actor = legalRepresentative(authentication)
        return BaseResponse.success(managedInstitutionProfileService.get(actor, institutionId))
    }

    @PutMapping("/{institutionId}")
    fun update(
        authentication: Authentication,
        @PathVariable institutionId: String,
        @RequestBody body: JsonNode
    ): BaseResponse<ManagedInstitutionProfile> {
        val actor = legalRepresentative(authentication)
        require(body.isObject) { "请求内容必须是 JSON 对象" }
        require(body.fieldNames().asSequence().toSet() == EDITABLE_KEYS) {
            "请求必须且只能包含机构档案的 13 个可编辑字段"
        }

        val establishedYear = body["establishedYear"].let { value ->
            when {
                value.isNull -> null
                value.isIntegralNumber && value.canConvertToInt() -> value.intValue()
                else -> throw IllegalArgumentException("establishedYear 必须是整数或 null")
            }
        }
        establishedYear?.let { year ->
            require(year in 1800..Year.now().value) {
                "establishedYear 必须在 1800 到当前年份之间"
            }
        }

        val command = ManagedInstitutionProfileUpdateCommand(
            name = body.requiredText("name").also { require(it.isNotBlank()) { "name 不能为空" } },
            address = body.requiredText("address"),
            city = body.requiredText("city"),
            description = body.requiredText("description"),
            coverImage = body.requiredText("coverImage"),
            images = body.requiredTextArray("images"),
            establishedYear = establishedYear,
            credentials = body.requiredText("credentials"),
            credentialImages = body.requiredTextArray("credentialImages"),
            specialties = body.requiredTextArray("specialties"),
            tags = body.requiredTextArray("tags"),
            contactPhone = body.requiredText("contactPhone"),
            businessHours = body.requiredText("businessHours")
        )
        return BaseResponse.success(managedInstitutionProfileService.update(actor, institutionId, command))
    }

    private fun legalRepresentative(authentication: Authentication): ManagementActor {
        val actor = managementAccessService.actor(authentication)
        if (actor.isAdmin || LEGAL_REPRESENTATIVE_ROLE !in actor.activeRoles) {
            throw AccessDeniedException("机构档案自助管理仅限已生效的机构法人")
        }
        return actor
    }

    private fun JsonNode.requiredText(key: String): String {
        val value = this[key]
        require(value != null && value.isTextual) { "$key 必须是字符串" }
        return value.asText()
    }

    private fun JsonNode.requiredTextArray(key: String): List<String> {
        val value = this[key]
        require(value != null && value.isArray) { "$key 必须是非空值数组" }
        return value.map { item ->
            require(item.isTextual) { "$key 的每一项都必须是字符串" }
            item.asText()
        }
    }

    private companion object {
        const val LEGAL_REPRESENTATIVE_ROLE = "INSTITUTION_LEGAL_REPRESENTATIVE"

        val EDITABLE_KEYS = setOf(
            "name",
            "address",
            "city",
            "description",
            "coverImage",
            "images",
            "establishedYear",
            "credentials",
            "credentialImages",
            "specialties",
            "tags",
            "contactPhone",
            "businessHours"
        )
    }
}
