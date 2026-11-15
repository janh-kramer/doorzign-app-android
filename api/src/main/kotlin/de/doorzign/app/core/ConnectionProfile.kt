package de.doorzign.app.core

import kotlinx.serialization.Serializable

/** Systemtyp – nur zur Anzeige. Die Logik verzweigt nie danach, sondern nach [SystemFeatures]. */
@Serializable
enum class SystemType { EDGE, CLOUD }

/**
 * Das eine aktive Verbindungsprofil.
 *
 * @param origins Basis-URLs ohne Pfad (z. B. `https://192.168.1.20:8443`); die zuletzt erfolgreiche steht vorn.
 * @param fingerprints Leer = Systemvertrauen (Cloud). Sonst SHA-256-Fingerprints (64 Hex, klein), entweder einer
 *   für alle URLs oder positionsweise gleich viele wie [origins].
 */
@Serializable
data class ConnectionProfile(
    val systemType: SystemType,
    val instanceId: String,
    val name: String,
    val origins: List<String>,
    val fingerprints: List<String> = emptyList(),
    /** API-Präfix; nur der Prism-Mock (Debug) nutzt "". */
    val apiPath: String = API_PATH,
) {
    init {
        require(origins.isNotEmpty()) { "Mindestens eine Adresse erforderlich" }
        require(fingerprints.size <= 1 || fingerprints.size == origins.size) {
            "Fingerprints müssen einer oder gleich viele wie Adressen sein"
        }
    }

    val primaryOrigin: String get() = origins.first()
    val apiBase: String get() = primaryOrigin.trimEnd('/') + apiPath
    val pinned: Boolean get() = fingerprints.isNotEmpty()

    /** Fingerprint, der für [origin] gilt, oder null bei Systemvertrauen. */
    fun fingerprintFor(origin: String): String? = when {
        fingerprints.isEmpty() -> null
        fingerprints.size == 1 -> fingerprints.first()
        else -> fingerprints[origins.indexOf(origin).coerceAtLeast(0)]
    }

    /** Alle Fingerprints, die für irgendeine URL dieses Profils gelten. */
    fun allFingerprints(): Set<String> = fingerprints.map { it.lowercase() }.toSet()

    /** Profil mit [origin] als bevorzugter URL (Reihenfolge der Fingerprints wird mitgeführt). */
    fun preferring(origin: String): ConnectionProfile {
        val idx = origins.indexOf(origin)
        if (idx <= 0) return this
        val newOrigins = listOf(origin) + origins.filterIndexed { i, _ -> i != idx }
        val newFps = if (fingerprints.size > 1) listOf(fingerprints[idx]) + fingerprints.filterIndexed { i, _ -> i != idx } else fingerprints
        return copy(origins = newOrigins, fingerprints = newFps)
    }

    companion object {
        const val API_PATH = "/mobile/v1"
        fun apiBase(origin: String): String = origin.trimEnd('/') + API_PATH
    }
}
