package com.joysong.server.user.service

object FixedAdminPhone {
    private val bareMainlandPhone = Regex("^1[0-9]{10}$")
    private val e164MainlandPhone = Regex("^\\+86(1[0-9]{10})$")

    fun isValidConfigured(phone: String): Boolean = bareMainlandPhone.matches(phone)

    fun isReserved(phone: String?, configuredPhone: String): Boolean =
        isValidConfigured(configuredPhone) && normalize(phone) == configuredPhone

    fun requireConfigured(phone: String, configuredPhone: String) {
        require(phone == configuredPhone && isValidConfigured(configuredPhone)) {
            "ADMIN_PHONE must be configured as a valid mobile number"
        }
    }

    fun e164Alias(configuredPhone: String): String {
        require(isValidConfigured(configuredPhone)) { "ADMIN_PHONE must be configured as a valid mobile number" }
        return "+86$configuredPhone"
    }

    private fun normalize(phone: String?): String? = when {
        phone == null -> null
        bareMainlandPhone.matches(phone) -> phone
        else -> e164MainlandPhone.matchEntire(phone)?.groupValues?.get(1)
    }
}
