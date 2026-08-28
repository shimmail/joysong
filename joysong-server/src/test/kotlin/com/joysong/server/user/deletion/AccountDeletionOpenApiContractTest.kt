package com.joysong.server.user.deletion

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path

class AccountDeletionOpenApiContractTest {
    private val json = jacksonObjectMapper().readTree(Path.of("openapi.json").toFile())
    private val yaml = YamlPropertySourceLoader()
        .load("account-deletion-openapi", FileSystemResource("openapi.yaml"))
        .single()

    @Test
    fun `step up authorization is a response-only field in both OpenAPI documents`() {
        val jsonAuthorization = json.at(
            "/components/schemas/AccountDeletionStepUpResponse/properties/deletionAuthorization",
        )

        assertTrue(jsonAuthorization.path("readOnly").booleanValue())
        assertFalse(jsonAuthorization.has("writeOnly"))
        assertEquals(
            true,
            yaml.getProperty(
                "components.schemas.AccountDeletionStepUpResponse.properties.deletionAuthorization.readOnly",
            ),
        )
        assertEquals(
            null,
            yaml.getProperty(
                "components.schemas.AccountDeletionStepUpResponse.properties.deletionAuthorization.writeOnly",
            ),
        )
    }

    @Test
    fun `send sms success response exposes the sms response schema in both OpenAPI documents`() {
        val expectedReference = "#/components/schemas/AccountDeletionSmsCodeResponse"
        val jsonReference = json.at(
            "/paths/~1api~1user~1account-deletion~1send-sms-code/post/responses/200/content/application~1json/schema/allOf/1/properties/data/\$ref",
        ).textValue()

        assertEquals(expectedReference, jsonReference)
        assertEquals(
            expectedReference,
            yaml.getProperty(
                "paths./api/user/account-deletion/send-sms-code.post.responses.200.content.application/json.schema.allOf[1].properties.data.\$ref",
            ),
        )
    }
}
