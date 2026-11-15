package de.doorzign.app.core

import de.doorzign.app.api.endpoints.AuthApi
import de.doorzign.app.api.endpoints.SystemApi
import de.doorzign.app.api.model.GetAuthProviders200Response
import de.doorzign.app.api.model.SystemInfo

/** Ergebnis des ersten Kontakts mit einem System (nur öffentliche Endpunkte). */
data class ProbeResult(
    val origin: String,
    val system: SystemInfo,
    /** Beobachteter Fingerprint (nur bei Trust.Capture/Pinned). */
    val observedFingerprint: String?,
)

object SystemProbe {
    /** `GET /system` an [origin] mit dem gegebenen Vertrauensmodell. */
    suspend fun probe(origin: String, trust: Trust, app: AppInfo, apiPath: String = ConnectionProfile.API_PATH): ProbeResult {
        val client = HttpClients.builder(trust)
            .addInterceptor(AuthInterceptor(InMemoryTokenStore(), app))
            .build()
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
     * Edge per QR: Adressen der Reihe nach probieren (jeweils mit dem zugehörigen Fingerprint gepinnt)
     * und `instanceId` prüfen. Liefert die erste erreichbare Adresse.
     */
    suspend fun probeEdge(pairing: PairingUri.Edge, app: AppInfo): ProbeResult {
        var last: ApiException? = null
        pairing.origins.forEachIndexed { i, origin ->
            val fp = if (pairing.fingerprints.size == 1) pairing.fingerprints[0] else pairing.fingerprints[i]
            try {
                val r = probe(origin, Trust.Pinned(setOf(fp)), app)
                if (r.system.instanceId != pairing.instanceId) {
                    throw ApiException(0, ApiException.INSTANCE_MISMATCH)
                }
                return r.copy(observedFingerprint = fp)
            } catch (e: ApiException) {
                if (e.code == ApiException.CERTIFICATE_MISMATCH || e.code == ApiException.INSTANCE_MISMATCH) throw e
                last = e
            }
        }
        throw last ?: ApiException(0, ApiException.NETWORK)
    }

    suspend fun providers(origin: String, trust: Trust, app: AppInfo): GetAuthProviders200Response {
        val client = HttpClients.builder(trust).addInterceptor(AuthInterceptor(InMemoryTokenStore(), app)).build()
        return apiCall { AuthApi(ConnectionProfile.apiBase(origin), client).getAuthProviders() }
    }
}
