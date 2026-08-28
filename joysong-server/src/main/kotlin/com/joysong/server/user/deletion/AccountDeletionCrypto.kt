package com.joysong.server.user.deletion

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class AccountDeletionCrypto(
    properties: AccountDeletionProperties,
) {
    private val key = properties.hmacSecret.toByteArray(Charsets.UTF_8).also {
        require(it.size >= 16) { "app.account-deletion.hmac-secret must contain at least 16 bytes" }
    }
    private val random = SecureRandom()

    fun hash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun matches(rawValue: String, expectedHash: String?): Boolean {
        if (expectedHash == null) return false
        return MessageDigest.isEqual(
            hash(rawValue).toByteArray(Charsets.US_ASCII),
            expectedHash.toByteArray(Charsets.US_ASCII),
        )
    }

    fun randomAuthorization(): String = ByteArray(48)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun randomSmsCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}
