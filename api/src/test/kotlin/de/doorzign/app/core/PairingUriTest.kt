package de.doorzign.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PairingUriTest {
    private val fp = "a".repeat(64)
    private val fp2 = "b".repeat(64)

    @Test fun edgeMitZweiAdressenUndEinemFingerprint() {
        val p = PairingUri.parse("doorzign://edge?u=https%3A%2F%2F192.168.1.20%3A8443,https%3A%2F%2Fgw.local%3A8443&fp=$fp&i=inst-1&n=Rathaus%20Nord")
        assertIs<PairingUri.Edge>(p)
        assertEquals(listOf("https://192.168.1.20:8443", "https://gw.local:8443"), p.origins)
        assertEquals(listOf(fp), p.fingerprints)
        assertEquals("inst-1", p.instanceId)
        assertEquals("Rathaus Nord", p.name)
    }

    @Test fun fingerprintMitDoppelpunktenUndGrossbuchstabenWirdNormalisiert() {
        val upper = fp2.uppercase().chunked(2).joinToString(":")
        val p = PairingUri.parse("doorzign://edge?u=https://10.0.0.1:8443&fp=$upper&i=x") as PairingUri.Edge
        assertEquals(listOf(fp2), p.fingerprints)
    }

    @Test fun positionsweiseFingerprints() {
        val p = PairingUri.parse("doorzign://edge?u=https://a:8443,https://b:8443&fp=$fp,$fp2&i=x") as PairingUri.Edge
        assertEquals(listOf(fp, fp2), p.fingerprints)
    }

    @Test fun falscheAnzahlFingerprints() {
        assertFailsWith<IllegalArgumentException> {
            PairingUri.parse("doorzign://edge?u=https://a,https://b,https://c&fp=$fp,$fp2&i=x")
        }
    }

    @Test fun httpWirdAbgelehnt() {
        assertFailsWith<IllegalArgumentException> { PairingUri.parse("doorzign://edge?u=http://a:8090&fp=$fp&i=x") }
    }

    @Test fun pfadWirdEntfernt() {
        val p = PairingUri.parse("doorzign://cloud?u=https%3A%2F%2FFirma.zeitwart.de%2F", allowCloud = true) as PairingUri.Cloud
        assertEquals("https://firma.zeitwart.de", p.origin)
    }

    @Test fun fremderCode() {
        assertFailsWith<IllegalArgumentException> { PairingUri.parse("https://example.com") }
    }

    @Test fun adresseingabe() {
        assertEquals("https://192.168.1.20:8443", AddressInput.edgeOrigin("192.168.1.20"))
        assertEquals("https://gw.local:9443", AddressInput.edgeOrigin("gw.local:9443"))
        assertEquals("https://firma.zeitwart.de", AddressInput.cloudOrigin("Firma"))
        assertEquals("https://zeit.example.org", AddressInput.cloudOrigin("zeit.example.org"))
        assertEquals("https://firma.zeitwart.de", AddressInput.cloudOrigin("https://firma.zeitwart.de/login"))
    }
}
