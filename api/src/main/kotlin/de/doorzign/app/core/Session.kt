package de.doorzign.app.core

import de.doorzign.app.api.endpoints.AuthApi
import de.doorzign.app.api.endpoints.CalendarsApi
import de.doorzign.app.api.endpoints.ContentsApi
import de.doorzign.app.api.endpoints.DisplaysApi
import de.doorzign.app.api.endpoints.JobsApi
import de.doorzign.app.api.endpoints.MeApi
import de.doorzign.app.api.endpoints.SchedulesApi
import de.doorzign.app.api.endpoints.SystemApi
import de.doorzign.app.api.infrastructure.ClientException
import de.doorzign.app.api.model.ExchangeSsoCodeRequest
import de.doorzign.app.api.model.LoginRequest
import de.doorzign.app.api.model.LogoutRequest
import de.doorzign.app.api.model.RefreshTokenRequest
import de.doorzign.app.api.model.TokenPair
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.OkHttpClient

/** Vertrauensmodell für TLS. */
sealed interface Trust {
    /** Systemzertifikate (Cloud). */
    data object System : Trust
    /** Nur Zertifikate mit diesen Fingerprints (Edge). */
    data class Pinned(val fingerprints: Set<String>) : Trust
    /** Erster Kontakt bei Trust on first use – nur für öffentliche Daten. */
    class Capture(val manager: CapturingTrustManager = CapturingTrustManager()) : Trust
}

object HttpClients {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun builder(trust: Trust): OkHttpClient.Builder {
        val b = base.newBuilder()
        val tm: X509TrustManager? = when (trust) {
            Trust.System -> null
            is Trust.Pinned -> PinningTrustManager(trust.fingerprints)
            is Trust.Capture -> trust.manager
        }
        if (tm != null) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            // Hostname wird nicht geprüft: Das Zertifikat selbst ist per Fingerprint festgelegt (IP-Adressen, .local).
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        return b
    }

    fun trustFor(profile: ConnectionProfile): Trust =
        if (profile.pinned) Trust.Pinned(profile.allFingerprints()) else Trust.System
}

/** Alle generierten Endpunkte, gebunden an eine Basis-URL und einen Client. */
class MobileApi(val apiBase: String, val client: OkHttpClient) {
    val system = SystemApi(apiBase, client)
    val auth = AuthApi(apiBase, client)
    val me = MeApi(apiBase, client)
    val displays = DisplaysApi(apiBase, client)
    val contents = ContentsApi(apiBase, client)
    val schedules = SchedulesApi(apiBase, client)
    val calendars = CalendarsApi(apiBase, client)
    val jobs = JobsApi(apiBase, client)
}

/**
 * Laufende Verbindung zu genau einem System. Wird beim Systemwechsel mit [close] beendet
 * (laufende Anfragen abbrechen, Cache schließen).
 *
 * @param cacheDir Verzeichnis nur für diese `instanceId`.
 */
class Session(
    val profile: ConnectionProfile,
    val tokens: TokenStore,
    private val app: AppInfo,
    cacheDir: File?,
    onSessionEnded: () -> Unit,
    onPreferredOriginChanged: (String) -> Unit = {},
) {
    private val trust = HttpClients.trustFor(profile)
    private val failover = OriginFailoverInterceptor(profile.origins, onPreferredOriginChanged)

    /** Client ohne Authenticator – für Refresh und Logout. */
    private val bareClient: OkHttpClient = HttpClients.builder(trust)
        .addInterceptor(failover)
        .addInterceptor(AuthInterceptor(NoTokens, app, profile.apiPath))
        .build()

    val client: OkHttpClient = HttpClients.builder(trust)
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "http"), 50L * 1024 * 1024)) }
        .addInterceptor(failover)
        .addInterceptor(AuthInterceptor(tokens, app, profile.apiPath))
        .authenticator(TokenAuthenticator(tokens, profile.apiPath, ::refresh, onSessionEnded))
        .build()

    val api = MobileApi(profile.apiBase, client)

    val isLoggedIn: Boolean get() = tokens.load() != null

    suspend fun login(username: String, password: String, deviceName: String, provider: String? = null) {
        val pair = apiCall {
            api.auth.login(LoginRequest(username, password, deviceName, provider, app.platform, app.version))
        }
        tokens.save(pair.toTokens())
    }

    suspend fun exchangeSsoCode(code: String, verifier: String) {
        val pair = apiCall { api.auth.exchangeSsoCode(ExchangeSsoCodeRequest(code, verifier)) }
        tokens.save(pair.toTokens())
    }

    /** Abmelden (best effort, max. 5 s) und Tokens löschen. */
    suspend fun logout() {
        val t = tokens.load()
        if (t != null) {
            withTimeoutOrNull(5_000) {
                runCatching {
                    apiCall {
                        AuthApi(api.apiBase, bareClient.newBuilder().addInterceptor { c ->
                            c.proceed(c.request().newBuilder().header("Authorization", "Bearer ${t.accessToken}").build())
                        }.build()).logout(LogoutRequest(t.refreshToken))
                    }
                }
            }
        }
        tokens.clear()
    }

    /** Laufende Anfragen abbrechen und Ressourcen freigeben. */
    fun close() {
        client.dispatcher.cancelAll()
        bareClient.dispatcher.cancelAll()
        runCatching { client.cache?.close() }
        client.connectionPool.evictAll()
    }

    private fun refresh(apiBase: String, refreshToken: String): RefreshResult = try {
        RefreshResult.Success(AuthApi(apiBase, bareClient).refreshToken(RefreshTokenRequest(refreshToken)).toTokens())
    } catch (e: ClientException) {
        if (e.statusCode == 401) RefreshResult.Rejected else RefreshResult.Failed
    } catch (e: IOException) {
        RefreshResult.Failed
    } catch (e: RuntimeException) {
        RefreshResult.Failed
    }

    private object NoTokens : TokenStore {
        override fun load(): Tokens? = null
        override fun save(tokens: Tokens) = Unit
        override fun clear() = Unit
    }
}

fun TokenPair.toTokens() = Tokens(accessToken, refreshToken, expiresAt)
