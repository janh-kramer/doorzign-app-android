package de.doorzign.app.core

import de.doorzign.app.api.endpoints.AuthApi
import de.doorzign.app.api.endpoints.SystemApi
import de.doorzign.app.api.model.GetAuthProviders200Response
import de.doorzign.app.api.model.SystemInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Ergebnis des ersten Kontakts mit einem System (nur öffentliche Endpunkte). */
data class ProbeResult(
    val origin: String,
    val system: SystemInfo,
    /** Beobachteter Fingerprint (nur bei Trust.Capture/Pinned). */
    val observedFingerprint: String?,
)

object SystemProbe {
    /** `GET /system` an [origin] mit dem gegebenen Vertrauensmodell. */
    suspend fun probe(
        origin: String,
        trust: Trust,
        app: AppInfo,
        apiPath: String = ConnectionProfile.API_PATH,
        client: OkHttpClient = client(trust, app),
    ): ProbeResult {
        val system = apiCall { SystemApi(origin.trimEnd('/') + apiPath, client).getSystem() }
        val fp = when (trust) {
            is Trust.Capture -> trust.manager.captured
            is Trust.Pinned -> system.tlsFingerprint?.lowercase()?.takeIf { it in trust.fingerprints }
                ?: trust.fingerprints.singleOrNull()
            Trust.System -> null
        }
        return ProbeResult(origin, system, fp)
    }

    /**
     * Edge per QR: alle Adressen gleichzeitig prüfen (jeweils mit dem zugehörigen Fingerprint gepinnt, `instanceId`
     * muss passen). Antwortet der Master (erste Adresse) innerhalb von [masterGraceMs] gültig, gewinnt er, sonst die
     * erste gültige Antwort; die übrigen Prüfungen werden abgebrochen (decisions 36).
     */
    suspend fun probeEdge(pairing: PairingUri.Edge, app: AppInfo, masterGraceMs: Long = MASTER_GRACE_MS): ProbeResult {
        val clients = pairing.origins.indices.map { i ->
            val fp = if (pairing.fingerprints.size == 1) pairing.fingerprints[0] else pairing.fingerprints[i]
            fp to client(Trust.Pinned(setOf(fp)), app)
        }
        return raceAddresses(
            count = pairing.origins.size,
            masterGraceMs = masterGraceMs,
            attempt = { i ->
                val (fp, client) = clients[i]
                val r = probe(pairing.origins[i], Trust.Pinned(setOf(fp)), app, client = client)
                if (r.system.instanceId != pairing.instanceId) throw ApiException(0, ApiException.INSTANCE_MISMATCH)
                r.copy(observedFingerprint = fp)
            },
            // Blockierende OkHttp-Aufrufe reagieren nicht auf Coroutine-Abbruch – Sockets direkt schließen.
            cancelAll = { clients.forEach { it.second.dispatcher.cancelAll() } },
        )
    }

    const val MASTER_GRACE_MS = 1_000L

    /**
     * Startet [attempt] für alle Indizes gleichzeitig. Index 0 (Master) gewinnt, wenn er innerhalb von [masterGraceMs]
     * erfolgreich ist, sonst der erste Erfolg. Danach werden die übrigen Versuche abgebrochen.
     * Scheitern alle, gilt: Fingerprint-Fehler vor `instanceId`-Fehler vor „nicht erreichbar“ (dann der Fehler des Masters).
     * Bewusst: Ist eine Adresse unerreichbar und antworten die übrigen nur ungültig, kommt der Fehler erst nach deren Timeout.
     */
    internal suspend fun <T> raceAddresses(
        count: Int,
        masterGraceMs: Long,
        attempt: suspend (Int) -> T,
        cancelAll: () -> Unit = {},
    ): T = coroutineScope {
        if (count == 0) throw ApiException(0, ApiException.NETWORK)
        val probes = (0 until count).map { i ->
            async {
                try { Result.success(attempt(i)) } catch (e: ApiException) { Result.failure<T>(e) }
            }
        }
        fun stop() { probes.forEach { it.cancel() }; cancelAll() }

        withTimeoutOrNull(masterGraceMs) { probes[0].await() }?.getOrNull()?.let { stop(); return@coroutineScope it }

        val errors = arrayOfNulls<ApiException>(count)
        val pending = probes.withIndex().toMutableList()
        while (pending.isNotEmpty()) {
            val (entry, result) = select { pending.forEach { e -> e.value.onAwait { e to it } } }
            pending.remove(entry)
            result.getOrNull()?.let { stop(); return@coroutineScope it }
            errors[entry.index] = result.exceptionOrNull() as ApiException
        }
        throw errors.firstOrNull { it?.code == ApiException.CERTIFICATE_MISMATCH }
            ?: errors.firstOrNull { it?.code == ApiException.INSTANCE_MISMATCH }
            ?: errors[0]!!
    }

    private fun client(trust: Trust, app: AppInfo): OkHttpClient =
        HttpClients.builder(trust).addInterceptor(AuthInterceptor(InMemoryTokenStore(), app)).build()

    suspend fun providers(origin: String, trust: Trust, app: AppInfo): GetAuthProviders200Response {
        val client = client(trust, app)
        return apiCall { AuthApi(ConnectionProfile.apiBase(origin), client).getAuthProviders() }
    }
}
