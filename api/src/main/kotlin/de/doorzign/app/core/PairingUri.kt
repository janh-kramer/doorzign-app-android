package de.doorzign.app.core

import java.net.URI
import java.net.URLDecoder

/** Ungültiger QR-Code bzw. ungültige Adresseingabe. Die App übersetzt [reason]. */
class PairingException(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason {
        NOT_DOORZIGN, NO_ADDRESS, BAD_FINGERPRINT, NO_FINGERPRINT, FINGERPRINT_COUNT, NO_INSTANCE,
        NOT_A_PAIRING_CODE, UNKNOWN_CODE, INVALID_ADDRESS, HTTPS_ONLY, EMPTY_INSTANCE, INVALID_INSTANCE,
    }
}

private fun fail(r: PairingException.Reason): Nothing = throw PairingException(r)
private fun ensure(ok: Boolean, r: PairingException.Reason) { if (!ok) fail(r) }

/** Inhalt eines QR-Codes bzw. Deep-Links (`doorzign://edge?…` / `doorzign://cloud?…`), siehe docs/edge-connection.md. */
sealed interface PairingUri {
    data class Edge(
        val origins: List<String>,
        val fingerprints: List<String>,
        val instanceId: String,
        val name: String?,
    ) : PairingUri

    data class Cloud(val origin: String, val name: String?) : PairingUri

    companion object {
        /** Parst den QR-Inhalt. Wirft [PairingException] bei ungültigem Inhalt. */
        fun parse(raw: String): PairingUri {
            val uri = runCatching { URI(raw.trim()) }.getOrElse { fail(PairingException.Reason.NOT_DOORZIGN) }
            ensure(uri.scheme.equals("doorzign", ignoreCase = true), PairingException.Reason.NOT_DOORZIGN)
            val params = parseQuery(uri.rawQuery.orEmpty())
            return when (uri.host?.lowercase()) {
                "edge" -> {
                    val origins = params["u"].orEmpty().map(::normalizeOrigin)
                    ensure(origins.isNotEmpty(), PairingException.Reason.NO_ADDRESS)
                    val fps = params["fp"].orEmpty().map {
                        Fingerprints.normalize(it) ?: fail(PairingException.Reason.BAD_FINGERPRINT)
                    }
                    ensure(fps.isNotEmpty(), PairingException.Reason.NO_FINGERPRINT)
                    ensure(fps.size == 1 || fps.size == origins.size, PairingException.Reason.FINGERPRINT_COUNT)
                    val id = params["i"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: fail(PairingException.Reason.NO_INSTANCE)
                    Edge(origins, fps, id, params["n"]?.firstOrNull())
                }
                "cloud" -> {
                    val origin = params["u"]?.firstOrNull() ?: fail(PairingException.Reason.NO_ADDRESS)
                    Cloud(normalizeOrigin(origin), params["n"]?.firstOrNull())
                }
                "auth" -> fail(PairingException.Reason.NOT_A_PAIRING_CODE)
                else -> fail(PairingException.Reason.UNKNOWN_CODE)
            }
        }

        /** Query → Map; Werte werden an Kommas getrennt und erst danach percent-dekodiert. */
        private fun parseQuery(raw: String): Map<String, List<String>> =
            raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to value.split(',').map(::decode).filter { it.isNotEmpty() }
            }

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

        /** Origin ohne Pfad und ohne abschließenden Schrägstrich; nur https. */
        fun normalizeOrigin(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrElse { fail(PairingException.Reason.INVALID_ADDRESS) }
            ensure(uri.scheme.equals("https", ignoreCase = true), PairingException.Reason.HTTPS_ONLY)
            ensure(!uri.host.isNullOrEmpty(), PairingException.Reason.INVALID_ADDRESS)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val host = if (uri.host.contains(':') && !uri.host.startsWith("[")) "[${uri.host}]" else uri.host
            return "https://${host.lowercase()}$port"
        }
    }
}

/** Eingaben aus „Adresse eingeben“ / Instanzfeld in Origins übersetzen. */
object AddressInput {
    const val EDGE_DEFAULT_PORT = 8443
    const val CLOUD_DOMAIN = "zeitwart.de"

    /** `192.168.1.20` → `https://192.168.1.20:8443`; `host:9443` und volle URLs bleiben erhalten. */
    fun edgeOrigin(input: String): String {
        val t = input.trim()
        if (t.contains("://")) return PairingUri.normalizeOrigin(t)
        val hasPort = Regex("^(\\[.*]|[^:]+):\\d+$").matches(t)
        return PairingUri.normalizeOrigin(if (hasPort) "https://$t" else "https://$t:$EDGE_DEFAULT_PORT")
    }

    /** `firma` → `https://firma.zeitwart.de`; volle URL oder Hostname mit Punkt bleiben erhalten. */
    fun cloudOrigin(input: String): String {
        val t = input.trim().trimEnd('/')
        ensure(t.isNotEmpty(), PairingException.Reason.EMPTY_INSTANCE)
        if (t.contains("://")) return PairingUri.normalizeOrigin(t)
        if (t.contains('.')) return PairingUri.normalizeOrigin("https://$t")
        ensure(Regex("^[a-zA-Z0-9-]+$").matches(t), PairingException.Reason.INVALID_INSTANCE)
        return "https://${t.lowercase()}.$CLOUD_DOMAIN"
    }
}
