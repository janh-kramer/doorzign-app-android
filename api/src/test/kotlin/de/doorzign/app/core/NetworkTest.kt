package de.doorzign.app.core

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class NetworkTest {
    private val expired = """{"error":{"code":"token_expired","message":"Abgelaufen"}}"""

    @Test fun gleichzeitige401LoesenGenauEinenRefreshAus() {
        val server = MockWebServer()
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.endsWith("/auth/refresh")) {
                    refreshes.incrementAndGet()
                    Thread.sleep(200)
                    return MockResponse().setBody("""{"accessToken":"neu","refreshToken":"r2","expiresAt":"2026-11-14T12:00:00Z"}""")
                }
                return if (request.getHeader("Authorization") == "Bearer neu") MockResponse().setBody("ok")
                else MockResponse().setResponseCode(401).setBody(expired)
            }
        }
        server.start()
        val store = InMemoryTokenStore(Tokens("alt", "r1"))
        var ended = false
        val client = clientWith(store, server) { ended = true }
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map {
            pool.submit<String> { client.newCall(Request.Builder().url(server.url("/mobile/v1/displays")).build()).execute().body!!.string() }
        }.map { it.get() }
        pool.shutdown()
        assertTrue(results.all { it == "ok" })
        assertEquals(1, refreshes.get())
        assertEquals("r2", store.load()!!.refreshToken)
        assertTrue(!ended)
        server.shutdown()
    }

    @Test fun unauthorizedFuehrtZurAnmeldung() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"Widerrufen"}}"""))
        server.start()
        val store = InMemoryTokenStore(Tokens("alt", "r1"))
        var ended = false
        val client = clientWith(store, server) { ended = true }
        val resp = client.newCall(Request.Builder().url(server.url("/mobile/v1/me")).build()).execute()
        assertEquals(401, resp.code)
        assertTrue(ended)
        assertNull(store.load())
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun abgelehnterRefreshFuehrtZurAnmeldung() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody(expired))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"x"}}"""))
        server.start()
        val store = InMemoryTokenStore(Tokens("alt", "r1"))
        var ended = false
        clientWith(store, server) { ended = true }
            .newCall(Request.Builder().url(server.url("/mobile/v1/me")).build()).execute()
        assertTrue(ended)
        assertNull(store.load())
        server.shutdown()
    }

    @Test fun pinningGegenVerbundCa() {
        val ca = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("irgendwas.local").signedBy(ca).build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build().sslSocketFactory(), false)
        repeat(3) { server.enqueue(MockResponse().setBody(SYSTEM_JSON)) }
        server.start()
        val origin = server.url("/").toString().trimEnd('/')
        val caFp = Fingerprints.of(ca.certificate)
        val app = AppInfo("1.0.0")

        // Hostname (localhost) passt nicht zum SAN – wird bewusst ignoriert.
        val ok = runBlocking { SystemProbe.probe(origin, Trust.Pinned(setOf(caFp)), app) }
        assertEquals("inst-1", ok.system.instanceId)

        val e = assertFailsWith<ApiException> {
            runBlocking { SystemProbe.probe(origin, Trust.Pinned(setOf("0".repeat(64))), app) }
        }
        assertEquals(ApiException.CERTIFICATE_MISMATCH, e.code)

        val captured = runBlocking { SystemProbe.probe(origin, Trust.Capture(), app) }
        assertEquals(caFp, captured.observedFingerprint)
        server.shutdown()
    }

    @Test fun gefaelschteKetteMitEchterCaWirdAbgelehnt() {
        val ca = HeldCertificate.Builder().certificateAuthority(0).build()
        val evil = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().signedBy(evil).build()
        val tm = PinningTrustManager(setOf(Fingerprints.of(ca.certificate)))
        // Angreifer schickt die echte CA mit, das Leaf ist aber nicht von ihr signiert.
        assertFailsWith<java.security.cert.CertificateException> {
            tm.checkServerTrusted(arrayOf(leaf.certificate, ca.certificate), "ECDHE_ECDSA")
        }
        // Echte Kette wird akzeptiert.
        val good = HeldCertificate.Builder().signedBy(ca).build()
        tm.checkServerTrusted(arrayOf(good.certificate, ca.certificate), "ECDHE_ECDSA")
    }

    @Test fun einzelnesSelbstsigniertesZertifikat() {
        val self = HeldCertificate.Builder().build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(self).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(SYSTEM_JSON))
        server.start()
        val origin = server.url("/").toString().trimEnd('/')
        val r = runBlocking { SystemProbe.probe(origin, Trust.Pinned(setOf(Fingerprints.of(self.certificate))), AppInfo("1.0.0")) }
        assertEquals("inst-1", r.system.instanceId)
        server.shutdown()
    }

    @Test fun failoverAufZweiteAdresse() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        val good = server.url("/").toString().trimEnd('/')
        val dead = "http://127.0.0.1:1" // Port 1: Verbindung wird abgelehnt
        var preferred: String? = null
        val client = OkHttpClient.Builder().addInterceptor(OriginFailoverInterceptor(listOf(dead, good)) { preferred = it }).build()
        val body = client.newCall(Request.Builder().url("$dead/mobile/v1/system").build()).execute().body!!.string()
        assertEquals("ok", body)
        assertEquals(good, preferred)
        assertEquals("/mobile/v1/system", server.takeRequest().path)
        server.shutdown()
    }

    @Test fun etagHeaderWerdenUnveraendertGesendetUnd304AusDemCache() {
        val server = MockWebServer()
        val list = """{"data":[]}"""
        server.enqueue(MockResponse().setBody(list).setHeader("ETag", "W/\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "W/\"v1\""))
        server.enqueue(MockResponse().setBody(SLOT_JSON))
        server.start()
        val dir = kotlin.io.path.createTempDirectory("dz").toFile()
        val profile = ConnectionProfile(SystemType.EDGE, "inst-1", "Test", listOf(server.url("/").toString().trimEnd('/')))
        val session = Session(profile, InMemoryTokenStore(Tokens("a", "r")), AppInfo("1.0.0"), dir, onSessionEnded = {})

        runBlocking {
            apiCall { session.api.displays.listDisplays() }
            // Zweiter Abruf: OkHttp-Cache fragt bedingt an, 304 liefert die zwischengespeicherte Liste
            assertEquals(0, apiCall { session.api.displays.listDisplays() }.`data`.size)
            apiCall { session.api.contents.updateSlot("d1", 1, SlotContent.Deactivated, ifMatch = "W/\"e1\"") }
        }
        server.takeRequest()
        assertEquals("W/\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        val put = server.takeRequest()
        assertEquals("W/\"e1\"", put.getHeader("If-Match"))
        assertEquals("Bearer a", put.getHeader("Authorization"))
        session.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test fun uploadSendetEchtenBinaerpartMitBildtyp() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"m1","mime":"image/png","width":1,"height":1}"""))
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":{"code":"too_large","message":"Datei ist zu groß"}}"""))
        server.start()
        val profile = ConnectionProfile(SystemType.CLOUD, "i", "T", listOf(server.url("/").toString().trimEnd('/')))
        val session = Session(profile, InMemoryTokenStore(Tokens("a", "r")), AppInfo("1.0.0"), null, onSessionEnded = {})
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0xFF.toByte())

        val media = runBlocking { session.uploadMedia(png, "image/png", "bild.png") }
        assertEquals("m1", media.id)
        val req = server.takeRequest()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val raw = req.body.readByteArray()
        val text = String(raw, Charsets.ISO_8859_1)
        assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"bild.png\""), text)
        assertTrue(text.contains("Content-Type: image/png"), text)
        // Binärdaten unverändert (inkl. 0x89 und 0xFF), nicht als Text kodiert
        assertTrue(String(png, Charsets.ISO_8859_1) in text)

        val e = assertFailsWith<ApiException> { runBlocking { session.uploadMedia(png, "image/png", "bild.png") } }
        assertEquals("too_large", e.code)
        session.close()
        server.shutdown()
    }

    private fun clientWith(store: TokenStore, server: MockWebServer, onEnded: () -> Unit): OkHttpClient {
        val bare = OkHttpClient()
        val auth = TokenAuthenticator(store, refresher = { base, rt ->
            val r = bare.newCall(Request.Builder().url("$base/auth/refresh")
                .post("""{"refreshToken":"$rt"}""".toRequestBody()).build()).execute()
            if (r.code == 401) RefreshResult.Rejected
            else RefreshResult.Success(de.doorzign.app.api.infrastructure.Serializer.kotlinxSerializationJson
                .decodeFromString<de.doorzign.app.api.model.TokenPair>(r.body!!.string()).toTokens())
        }, onSessionEnded = onEnded)
        return OkHttpClient.Builder().addInterceptor(AuthInterceptor(store, AppInfo("1.0.0"))).authenticator(auth).build()
    }

    companion object {
        const val SLOT_JSON = """{"slot":1,"content":{"type":"deactivated"},"transferred":false,"editable":true,"etag":"e2"}"""
        const val SYSTEM_JSON = """{"systemType":"edge","instanceId":"inst-1","name":"Rathaus","apiVersion":"1.0",
            "minAppVersion":"1.0.0","features":["adhoc"],"timezone":"Europe/Berlin","mediaMaxBytes":20000000}"""
    }
}
