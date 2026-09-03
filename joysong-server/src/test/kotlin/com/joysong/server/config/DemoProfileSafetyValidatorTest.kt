package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class DemoProfileSafetyValidatorTest {

    @Test
    fun `demo profile permits explicitly enabled OSS`() {
        val environment = safeDemoEnvironment()
            .withProperty("oss.enabled", "true")

        assertDoesNotThrow {
            DemoProfileSafetyValidator().validate(environment)
        }
    }

    @Test
    fun `demo profile still rejects other external services`() {
        val environment = safeDemoEnvironment()
            .withProperty("aliyun.sms.enabled", "true")

        val error = assertThrows(IllegalStateException::class.java) {
            DemoProfileSafetyValidator().validate(environment)
        }

        assertTrue(error.message.orEmpty().contains("aliyun.sms.enabled must be false"))
    }

    private fun safeDemoEnvironment() = MockEnvironment().apply {
        setActiveProfiles("demo")
        withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/myapp_worktree_demo_test")
        withProperty("seed.demo.expected-database-name", "myapp_worktree_demo_test")
        withProperty("spring.jpa.hibernate.ddl-auto", "validate")
        withProperty("spring.flyway.enabled", "true")
        withProperty("spring.flyway.validate-on-migrate", "true")
        withProperty("spring.flyway.baseline-on-migrate", "false")
        withProperty("spring.flyway.clean-disabled", "true")
    }
}
