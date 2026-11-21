package de.doorzign.app.data

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.doorzign.app.R
import de.doorzign.app.api.model.SsoProvider
import de.doorzign.app.ui.common.Texts
import de.doorzign.app.api.model.SystemInfo
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.AppInfo
import de.doorzign.app.core.Compatibility
import de.doorzign.app.core.ConnectionProfile
import de.doorzign.app.core.Fingerprints
import de.doorzign.app.core.Pkce
import de.doorzign.app.core.Session
import de.doorzign.app.core.Sso
import de.doorzign.app.core.SystemFeatures
import de.doorzign.app.core.TokenStore
import de.doorzign.app.core.Versions
import de.doorzign.app.core.apiCall
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Eine aktive Verbindung: Profil, HTTP-Sitzung, Bildlader und Cache-Verzeichnis dieser `instanceId`. */
class Connection(
    val profile: ConnectionProfile,
    val session: Session,
    val imageLoader: ImageLoader,
    val cacheDir: File,
) {
    val api get() = session.api
}

sealed interface AppState {
    data object Onboarding : AppState
    data class SignedOut(val conn: Connection, val notice: String? = null) : AppState
    data class SignedIn(val conn: Connection) : AppState
}

sealed interface SsoStatus {
    data object Idle : SsoStatus
    data object Working : SsoStatus
    data class Failed(val message: String) : SsoStatus
    /** Nutzer kam ohne Callback aus dem Browser zurück (z. B. Tab geschlossen, IdP-Fehler) – nur Hinweis, kein Fehler. */
    data object Cancelled : SsoStatus
}

/**
 * Hält genau ein aktives Verbindungsprofil. Systemwechsel/Abmelden: laufende Anfragen abbrechen →
 * `POST /auth/logout` (best effort) → Tokens, Cache und Profil löschen → Onboarding.
 */
class SessionManager(
    private val context: Context,
    private val tokens: TokenStore,
    private val profiles: ProfileStore,
    private val ssoStore: PendingSsoStore,
    val appInfo: AppInfo,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AppState>(AppState.Onboarding)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _system = MutableStateFlow<SystemInfo?>(null)
    val system: StateFlow<SystemInfo?> = _system.asStateFlow()

    private val _compat = MutableStateFlow(Compatibility.OK)
    val compatibility: StateFlow<Compatibility> = _compat.asStateFlow()

    private val _sso = MutableStateFlow<SsoStatus>(SsoStatus.Idle)
    val sso: StateFlow<SsoStatus> = _sso.asStateFlow()

    /** QR-/Deep-Link-Inhalt, der vom Onboarding übernommen werden soll. */
    /** Roher Verbindungslink (`doorzign://edge|cloud…`) aus einem Intent; das Onboarding prüft und meldet Fehler. */
    val pendingPairing = MutableStateFlow<String?>(null)

    val features: SystemFeatures get() = SystemFeatures(_system.value?.features.orEmpty())

    val connection: Connection?
        get() = when (val s = _state.value) {
            is AppState.SignedIn -> s.conn
            is AppState.SignedOut -> s.conn
            AppState.Onboarding -> null
        }

    init {
        profiles.load()?.let { p ->
            profiles.loadSystem()?.let(::applySystem)
            open(p)
            if (tokens.load() != null) scope.launch { refreshSystem() }
        }
    }

    /** Neues Profil nach erfolgreicher Prüfung übernehmen (Onboarding → Anmeldung). */
    fun activate(profile: ConnectionProfile, system: SystemInfo) {
        check(connection == null) { "Es ist bereits ein System verbunden" }
        tokens.clear()
        profiles.save(profile)
        profiles.saveSystem(system)
        applySystem(system)
        open(profile)
    }

    private fun open(profile: ConnectionProfile) {
        val dir = instanceDir(profile.instanceId).apply { mkdirs() }
        val session = Session(
            profile = profile,
            tokens = tokens,
            app = appInfo,
            cacheDir = dir,
            onSessionEnded = ::onSessionEnded,
            onPreferredOriginChanged = { origin -> profiles.save(profile.preferring(origin)) },
        )
        val loader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { session.client })) }
            .diskCache { DiskCache.Builder().directory(File(dir, "images")).maxSizeBytes(50L * 1024 * 1024).build() }
            .build()
        val conn = Connection(profile, session, loader, dir)
        _state.value = if (tokens.load() != null) AppState.SignedIn(conn) else AppState.SignedOut(conn)
    }

    private fun onSessionEnded() {
        val conn = connection ?: return
        _state.value = AppState.SignedOut(conn, Texts.get(R.string.session_expired))
    }

    suspend fun login(username: String, password: String, deviceName: String, provider: String?) {
        val conn = connection ?: return
        conn.session.login(username, password, deviceName, provider)
        _state.value = AppState.SignedIn(conn)
        refreshSystem()
    }

    /** `GET /system` neu laden: Features, Kompatibilität, Prüfung der instanceId. */
    suspend fun refreshSystem() {
        val conn = connection ?: return
        val sys = runCatching { apiCall { conn.api.system.getSystem() } }.getOrNull() ?: return
        if (sys.instanceId != conn.profile.instanceId) {
            disconnect()
            return
        }
        profiles.saveSystem(sys)
        applySystem(sys)
    }

    private fun applySystem(sys: SystemInfo) {
        _system.value = sys
        _compat.value = Versions.check(sys.apiVersion, sys.minAppVersion, appInfo.version)
    }

    /** Abmelden bzw. mit anderem System verbinden: alles zu diesem System wird gelöscht. */
    suspend fun disconnect() {
        val conn = connection
        _state.value = AppState.Onboarding
        if (conn != null) {
            conn.session.client.dispatcher.cancelAll()
            conn.session.logout()
            conn.session.close()
            conn.imageLoader.memoryCache?.clear()
            conn.imageLoader.shutdown()
        }
        tokens.clear()
        ssoStore.clear()
        profiles.clear()
        _system.value = null
        _compat.value = Compatibility.OK
        withContext(Dispatchers.IO) { File(context.cacheDir, INSTANCES).deleteRecursively() }
    }

    // --- SSO (Custom Tabs + PKCE) ---

    fun startSso(activity: Context, provider: SsoProvider, deviceName: String) {
        val conn = connection ?: return
        val pkce = Pkce.create()
        ssoStore.save(PendingSsoStore.Pending(pkce.state, pkce.verifier, conn.profile.instanceId, System.currentTimeMillis()))
        val url = conn.profile.apiBase.toHttpUrl().newBuilder()
            .addPathSegments("auth/sso/start")
            .addQueryParameter("provider", provider.id)
            .addQueryParameter("redirect_uri", Sso.REDIRECT_URI)
            .addQueryParameter("state", pkce.state)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("device_name", deviceName)
            .addQueryParameter("platform", appInfo.platform)
            .addQueryParameter("app_version", appInfo.version)
            .build()
        _sso.value = SsoStatus.Idle
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        // Ephemere Sitzung ohne vorhandene Zeitwart-Cookies (sonst endet der Abschluss mit access_denied).
        // Extra aus androidx.browser 1.9 (EXTRA_ENABLE_EPHEMERAL_BROWSING); Browser ohne Unterstützung ignorieren es.
        tab.intent.putExtra(EXTRA_EPHEMERAL, true)
        tab.launchUrl(activity, Uri.parse(url.toString()))
    }

    /** `doorzign://auth/callback?code=…&state=…` bzw. `?error=…`. */
    fun handleSsoCallback(uri: Uri) {
        val pending = ssoStore.take()
        val conn = connection
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        when {
            pending == null || conn == null || pending.state != state || pending.instanceId != conn.profile.instanceId ->
                _sso.value = SsoStatus.Failed(Texts.get(R.string.sso_unmatched))
            System.currentTimeMillis() - pending.startedAt > SSO_MAX_AGE_MS ->
                _sso.value = SsoStatus.Failed(Texts.get(R.string.sso_expired))
            error != null -> _sso.value = SsoStatus.Failed(ssoErrorText(error))
            code.isNullOrEmpty() -> _sso.value = SsoStatus.Failed(Texts.get(R.string.sso_failed))
            else -> {
                _sso.value = SsoStatus.Working
                scope.launch {
                    try {
                        conn.session.exchangeSsoCode(code, pending.verifier)
                        _sso.value = SsoStatus.Idle
                        _state.value = AppState.SignedIn(conn)
                        refreshSystem()
                    } catch (e: ApiException) {
                        // invalid_grant: Code abgelaufen (60 s), schon benutzt oder Verifier falsch
                        _sso.value = SsoStatus.Failed(
                            if (e.code == "invalid_grant") Texts.get(R.string.sso_code_invalid) else Texts.error(e))
                    }
                }
            }
        }
    }

    fun clearSsoStatus() { _sso.value = SsoStatus.Idle }

    /**
     * Rückkehr in die App ohne `doorzign://auth/callback` (parity.md: scheitert der Login beim IdP, kommt kein
     * Callback). State und Verifier werden verworfen; nur ein frischer Vorgang erzeugt den Hinweis „abgebrochen“.
     */
    fun onResumedWithoutSsoCallback() {
        val since = ssoStore.pendingSince() ?: return
        ssoStore.clear()
        if (System.currentTimeMillis() - since <= SSO_MAX_AGE_MS && _sso.value !is SsoStatus.Working) _sso.value = SsoStatus.Cancelled
    }

    private fun ssoErrorText(code: String) = when (code) {
        "access_denied" -> Texts.get(R.string.sso_denied)
        "provider_error" -> Texts.get(R.string.sso_provider_error)
        "expired" -> Texts.get(R.string.sso_expired)
        "invalid_request" -> Texts.get(R.string.sso_invalid_request)
        else -> Texts.get(R.string.sso_provider_error) // provider_error und Unbekanntes (glossary.md)
    }

    private fun instanceDir(instanceId: String) =
        File(File(context.cacheDir, INSTANCES), Fingerprints.sha256Hex(instanceId.toByteArray()).take(32))

    private companion object {
        const val INSTANCES = "instances"
        const val SSO_MAX_AGE_MS = 10 * 60 * 1000L
        const val EXTRA_EPHEMERAL = "androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING"
    }
}
