package com.joysong.server.user.deletion

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

fun interface GoogleAccountDeletionVerifier {
    fun verify(idToken: String): VerifiedGoogleIdentity
}

@Component
class GoogleAccountDeletionVerifierImpl(
    @Value("\${google.client-id:}") private val googleClientId: String,
    @Value("\${google.proxy-url:}") private val googleProxyUrl: String,
) : GoogleAccountDeletionVerifier {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val verifier: GoogleIdTokenVerifier by lazy {
        val transport = if (googleProxyUrl.isNotBlank()) {
            val proxyUri = java.net.URI(googleProxyUrl)
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(proxyUri.host, proxyUri.port),
            )
            NetHttpTransport.Builder().setProxy(proxy).build()
        } else {
            NetHttpTransport()
        }
        GoogleIdTokenVerifier.Builder(transport, GsonFactory.getDefaultInstance())
            .setAudience(listOf(googleClientId))
            .build()
    }

    override fun verify(idToken: String): VerifiedGoogleIdentity {
        if (googleClientId.isBlank() || idToken.isBlank()) verificationFailed()
        val token = try {
            verifier.verify(idToken) ?: verificationFailed()
        } catch (error: java.security.GeneralSecurityException) {
            logger.warn("Google account deletion reauthentication failed")
            verificationFailed()
        } catch (error: java.io.IOException) {
            logger.warn("Google account deletion verifier unavailable")
            throw AccountDeletionException(AccountDeletionErrorCode.BLOCKER_SERVICE_UNAVAILABLE)
        }
        val payload = token.payload
        val issuer = payload.issuer.orEmpty()
        val audience = payload.audienceAsList.singleOrNull().orEmpty()
        val email = payload.email.orEmpty()
        val emailVerified = payload.emailVerified == true
        if (issuer !in ACCEPTED_ISSUERS || audience != googleClientId || !emailVerified || email.isBlank()) {
            verificationFailed()
        }
        return VerifiedGoogleIdentity(issuer, audience, email, emailVerified)
    }

    private fun verificationFailed(): Nothing =
        throw AccountDeletionException(AccountDeletionErrorCode.VERIFICATION_FAILED)

    private companion object {
        val ACCEPTED_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
