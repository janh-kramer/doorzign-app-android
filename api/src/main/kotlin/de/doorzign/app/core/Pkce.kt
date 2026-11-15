package de.doorzign.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636, S256) für den SSO-Ablauf im Systembrowser. */
data class Pkce(val verifier: String, val challenge: String, val state: String) {
    companion object {
        private val random = SecureRandom()
        private val b64 = Base64.getUrlEncoder().withoutPadding()

        fun create(): Pkce {
            val verifier = randomString(32)
            return Pkce(verifier, challengeOf(verifier), randomString(16))
        }

        fun challengeOf(verifier: String): String =
            b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun randomString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(b64::encodeToString)
    }
}

object Sso {
    const val REDIRECT_URI = "doorzign://auth/callback"
}
