package com.joysong.server.user.deletion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class AccountDeletionAvailabilityTest {
    @Test
    fun `production remains hard disabled even when bound properties are true`() {
        val properties = AccountDeletionProperties().apply {
            enabled = true
            allowCommerceBypass = true
        }
        val availability = AccountDeletionAvailability(
            properties,
            MockEnvironment().apply { setActiveProfiles("prod") },
        )

        val error = assertThrows(AccountDeletionException::class.java) {
            availability.requireEnabled()
        }

        assertEquals(AccountDeletionErrorCode.FEATURE_DISABLED, error.errorCode)
        assertFalse(availability.isEnabled())
        assertFalse(availability.commerceBypassAllowed())
    }

    @Test
    fun `non production bypass requires both explicit switches`() {
        val properties = AccountDeletionProperties()
        val availability = AccountDeletionAvailability(properties, MockEnvironment().apply { setActiveProfiles("dev") })

        properties.enabled = true
        assertFalse(availability.commerceBypassAllowed())
        properties.allowCommerceBypass = true
        assertTrue(availability.commerceBypassAllowed())
    }
}
