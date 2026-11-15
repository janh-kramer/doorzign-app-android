package de.doorzign.app.core

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Fingerprint-Format laut docs/edge-connection.md: SHA-256 über DER des CA-Zertifikats, 64 Hex klein. */
object Fingerprints {
    fun of(cert: X509Certificate): String = sha256Hex(cert.encoded)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Normalisiert Eingaben mit Doppelpunkten/Leerzeichen/Großbuchstaben. Null, wenn kein gültiger Fingerprint. */
    fun normalize(raw: String): String? {
        val hex = raw.filter { it.isLetterOrDigit() }.lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Anzeige in Blöcken à 4 Zeichen (Trust on first use). */
    fun blocks(fp: String): String = fp.chunked(4).joinToString(" ")
}

/** Das Serverzertifikat passt nicht zum gepinnten Fingerprint. */
class CertificateMismatchException(val actual: String) :
    CertificateException("Zertifikat hat sich geändert ($actual)")

/**
 * Prüft eine Serverkette gegen eine gepinnte CA (docs/edge-connection.md):
 * Die Kette muss ein Zertifikat mit dem Fingerprint enthalten, und jedes Zertifikat bis dorthin muss vom
 * nächsten signiert sein. Hostname und Aussteller-Vertrauen des Systems spielen keine Rolle.
 * Liefert den Index des gepinnten Zertifikats.
 */
internal object ChainCheck {
    fun verifyUpTo(chain: Array<out X509Certificate>, anchorIndex: Int) {
        for (i in 0 until anchorIndex) {
            try {
                chain[i].verify(chain[i + 1].publicKey)
            } catch (e: Exception) {
                throw CertificateException("Zertifikatskette ungültig", e)
            }
        }
        if (anchorIndex > 0 && chain[anchorIndex].basicConstraints < 0) {
            throw CertificateException("Gepinntes Zertifikat ist keine CA")
        }
        chain[0].checkValidity()
    }
}

/**
 * TrustManager für Edge: vertraut Serverketten, die zu einer der gepinnten CAs führen.
 * Der Server MUSS das CA-Zertifikat in der TLS-Kette mitsenden (bei einem einzelnen selbstsignierten
 * Zertifikat ist es Leaf und CA zugleich).
 */
class PinningTrustManager(expected: Set<String>) : X509TrustManager {
    private val expected = expected.map { it.lowercase() }.toSet()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Kein Serverzertifikat")
        val idx = chain.indexOfFirst { Fingerprints.of(it) in expected }
        if (idx < 0) throw CertificateMismatchException(Fingerprints.of(chain.last()))
        ChainCheck.verifyUpTo(chain, idx)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Clientzertifikate werden nicht unterstützt")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Nur für den ersten Kontakt bei Trust on first use: akzeptiert eine in sich gültige Kette und merkt sich den
 * Fingerprint ihres letzten Zertifikats (der CA). Darüber werden ausschließlich öffentliche Daten
 * (`GET /system`) geladen, niemals Zugangsdaten.
 */
class CapturingTrustManager : X509TrustManager {
    @Volatile var captured: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Kein Serverzertifikat")
        ChainCheck.verifyUpTo(chain, chain.lastIndex)
        captured = Fingerprints.of(chain.last())
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Clientzertifikate werden nicht unterstützt")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
