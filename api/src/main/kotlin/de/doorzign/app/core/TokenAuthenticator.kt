package de.doorzign.app.core

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import de.doorzign.app.api.infrastructure.Serializer
import de.doorzign.app.api.model.ErrorResponse

/** Ergebnis eines Refresh-Versuchs. */
sealed interface RefreshResult {
    data class Success(val tokens: Tokens) : RefreshResult
    /** Backend lehnt den Refresh-Token ab (401) → neu anmelden. */
    data object Rejected : RefreshResult
    /** Netzwerk-/Serverfehler → Sitzung behalten, aktuellen Aufruf scheitern lassen. */
    data object Failed : RefreshResult
}

/**
 * Serialisierter Token-Refresh bei 401.
 *
 * - `token_expired` → genau ein `POST /auth/refresh` für alle gleichzeitig wartenden Anfragen, dann Wiederholung.
 * - Hat inzwischen eine andere Anfrage refresht (Token im Request ≠ gespeicherter Token) → mit neuem Token wiederholen.
 * - Jeder andere 401 oder abgelehnter Refresh → Tokens löschen, [onSessionEnded] (zur Anmeldung).
 *
 * @param refresher führt den Refresh gegen die API-Basis-URL aus (ohne diesen Authenticator!).
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val apiPath: String = ConnectionProfile.API_PATH,
    private val refresher: (apiBase: String, refreshToken: String) -> RefreshResult,
    private val onSessionEnded: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        if (responseCount(response) > 2) return null
        val code = errorCode(response)

        synchronized(lock) {
            val current = tokens.load() ?: return null
            if (current.accessToken != sent) return withToken(response.request, current)
            if (code != "token_expired") {
                tokens.clear()
                onSessionEnded()
                return null
            }
            return when (val r = refresher(apiBaseOf(response.request, apiPath), current.refreshToken)) {
                is RefreshResult.Success -> {
                    tokens.save(r.tokens)
                    withToken(response.request, r.tokens)
                }
                RefreshResult.Rejected -> {
                    tokens.clear()
                    onSessionEnded()
                    null
                }
                RefreshResult.Failed -> null
            }
        }
    }

    private fun withToken(req: Request, t: Tokens) =
        req.newBuilder().header("Authorization", "Bearer ${t.accessToken}").build()

    private fun responseCount(r: Response): Int {
        var n = 1
        var p = r.priorResponse
        while (p != null) { n++; p = p.priorResponse }
        return n
    }

    companion object {
        fun errorCode(response: Response): String? = runCatching {
            Serializer.kotlinxSerializationJson.decodeFromString<ErrorResponse>(response.peekBody(16_384).string()).error.code
        }.getOrNull()

        fun apiBaseOf(req: Request, apiPath: String = ConnectionProfile.API_PATH): String {
            val u = req.url
            val defaultPort = (u.scheme == "https" && u.port == 443) || (u.scheme == "http" && u.port == 80)
            val host = if (u.host.contains(':')) "[${u.host}]" else u.host
            return "${u.scheme}://$host${if (defaultPort) "" else ":${u.port}"}$apiPath"
        }
    }
}
