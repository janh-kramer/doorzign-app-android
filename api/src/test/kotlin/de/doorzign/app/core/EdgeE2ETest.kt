package de.doorzign.app.core

import de.doorzign.app.api.model.Display
import de.doorzign.app.api.model.RefreshTokenRequest
import de.doorzign.app.api.model.ScheduleInput
import de.doorzign.app.api.model.SetActiveSlotRequest
import de.doorzign.app.api.model.StartAdhocRequest
import de.doorzign.app.api.infrastructure.Success
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue

/**
 * Ende-zu-Ende gegen ein echtes Edge-Backend (Konformitätsumgebung der Koordination).
 * Läuft nur mit `E2E_EDGE_URL=https://host.docker.internal:8453 E2E_EDGE_FP=<ca-fp> ./build.sh :api:test --tests '*EdgeE2E*'`.
 * Hinterlässt den Ausgangszustand: Display A auf Ansicht 1, keine Ad-hoc-Nachricht, Ansicht 2 unverändert,
 * angelegter Zeitplan gelöscht.
 */
class EdgeE2ETest {
    private val url = System.getenv("E2E_EDGE_URL").orEmpty()
    private val fp = System.getenv("E2E_EDGE_FP").orEmpty()
    private val app = AppInfo("1.0.0")
    private val password = "conformance"

    private fun enabled() = assumeTrue("E2E_EDGE_URL/E2E_EDGE_FP nicht gesetzt", url.isNotEmpty() && fp.isNotEmpty())

    private fun session(cacheDir: File? = null, onEnded: () -> Unit = {}): Session {
        val profile = ConnectionProfile(SystemType.EDGE, "e2e", "E2E", listOf(url), listOf(fp))
        return Session(profile, InMemoryTokenStore(), app, cacheDir, onSessionEnded = onEnded)
    }

    private suspend fun Session.loginAs(role: String) = login("mobile-conformance-$role", password, "Android-E2E $role")

    private suspend fun Session.displayA(): Display =
        apiCall { api.displays.listDisplays() }.`data`.first { it.name == "Konformität A" }

    private fun log(s: String) = println("[E2E] $s")

    /** Wartet, bis der Auftrag einen Endzustand erreicht (Qbic-Simulator arbeitet wirklich). */
    private suspend fun Session.awaitJob(displayId: String, jobId: String, timeoutMs: Long = 120_000): String {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val job = apiCall { api.jobs.listDisplayJobs(displayId) }.`data`.firstOrNull { it.id == jobId }
            val st = job?.status?.value
            if (st != null && !JobStates.isOpen(st)) return st
            delay(2_000)
        }
        error("Auftrag $jobId nach ${timeoutMs / 1000} s nicht abgeschlossen")
    }

    @Test fun t1_pinningGegenCaUndHostnameWirdIgnoriert() = runBlocking<Unit> {
        enabled()
        // host.docker.internal steht NICHT im SAN – Verbindung muss trotzdem klappen (nur CA-Fingerprint zählt).
        val ok = SystemProbe.probe(url, Trust.Pinned(setOf(fp)), app)
        assertEquals("edge", ok.system.systemType.value)
        assertEquals(fp, ok.system.tlsFingerprint?.lowercase())
        log("Pinning OK, instanceId=${ok.system.instanceId}, features=${ok.system.features}")

        val wrong = assertFailsWith<ApiException> { SystemProbe.probe(url, Trust.Pinned(setOf("0".repeat(64))), app) }
        assertEquals(ApiException.CERTIFICATE_MISMATCH, wrong.code)

        // TOFU zeigt den CA-Fingerprint (letztes Zertifikat der Kette)
        assertEquals(fp, SystemProbe.probe(url, Trust.Capture(), app).observedFingerprint)

        // Systemvertrauen (Cloud-Modus) muss das selbstsignierte Zertifikat ablehnen
        val sys = assertFailsWith<ApiException> { SystemProbe.probe(url, Trust.System, app) }
        assertEquals(ApiException.TLS, sys.code)
    }

    @Test fun t2_editAblaufMitEchtenAuftraegen() = runBlocking<Unit> {
        enabled()
        val dir = kotlin.io.path.createTempDirectory("e2e").toFile()
        val s = session(dir)
        s.loginAs("edit")
        val a = s.displayA()
        assertEquals("edit", a.level.value)
        val original2 = apiCall { s.api.contents.getSlot(a.id, 2) }
        var scheduleId: String? = null
        try {
            // Ansicht 2 speichern mit If-Match, danach veraltetes ETag → 412
            val saved = apiCall { s.api.contents.updateSlot(a.id, 2, SlotContent.Text(title = "E2E Android", message = "Test"), ifMatch = original2.etag) }
            assertNotEquals(original2.etag, saved.etag)
            val stale = assertFailsWith<ApiException> {
                apiCall { s.api.contents.updateSlot(a.id, 2, SlotContent.Text(title = "veraltet"), ifMatch = original2.etag) }
            }
            assertEquals(412, stale.status)
            log("If-Match/412 OK")

            // Live-Vorschau (ungespeichert) liefert PNG
            val png = s.renderPreview(a.id, 2, SlotContent.Text(title = "Vorschau"))
            assertTrue(png.size > 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte())

            // preview.png zweimal: zweiter Abruf bedingt mit exaktem ETag → 304 aus dem HTTP-Cache
            val pUrl = "${s.profile.apiBase}/displays/${a.id}/slots/1/preview.png"
            s.client.newCall(Request.Builder().url(pUrl).build()).execute().use { assertEquals(200, it.code); log("Protokoll: ${it.protocol}"); it.body!!.bytes() }
            s.client.newCall(Request.Builder().url(pUrl).build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals(304, r.networkResponse?.code, "zweiter Abruf sollte 304 vom Server bekommen")
            }
            log("preview.png ETag/304 OK")

            // Umschalten auf Ansicht 2 → Auftrag läuft über den Simulator durch
            val job = apiCall { s.api.displays.setActiveSlot(a.id, SetActiveSlotRequest(2)) }
            val st = s.awaitJob(a.id, job.id)
            log("press_button → $st")
            assertEquals("done", st)

            // Identify
            apiCall { s.api.displays.identifyDisplay(a.id) }

            // Ad-hoc starten, lesen, beenden
            val ad = apiCall { s.api.contents.startAdhoc(a.id, StartAdhocRequest(TextContent(message = "E2E Android"), 15)) }
            assertEquals("E2E Android", ad.content.message)
            val running = apiCall { s.api.contents.getAdhocWithHttpInfo(a.id) }
            assertTrue(running is Success<*> && running.statusCode == 200)
            apiCall { s.api.contents.endAdhoc(a.id) }
            assertEquals(204, apiCall { s.api.contents.getAdhocWithHttpInfo(a.id) }.statusCode)
            log("Ad-hoc OK")

            // Zeitplan anlegen, mit If-Match ändern, löschen
            val created = apiCall { s.api.schedules.createSchedule(a.id, ScheduleInput(1, setOf(6, 7), "22:00", "06:00", 10, false, "E2E Android")) }
            scheduleId = created.id
            val upd = apiCall { s.api.schedules.updateSchedule(created.id, ScheduleInput(1, setOf(6), "22:00", "06:00", 11, false, "E2E Android"), ifMatch = created.etag) }
            assertEquals(11, upd.priority)
            assertEquals(412, assertFailsWith<ApiException> {
                apiCall { s.api.schedules.updateSchedule(created.id, ScheduleInput(1, setOf(6), "22:00", "06:00", 12, false), ifMatch = created.etag) }
            }.status)
            apiCall { s.api.schedules.deleteSchedule(created.id) }
            scheduleId = null
            log("Zeitpläne OK")

            // Upload (echter Binär-Part) → Bibliothek → Original abrufen
            val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            val media = s.uploadMedia(bytes, "image/png", "e2e-android.png")
            assertEquals(64, media.width)
            assertTrue(apiCall { s.api.contents.listMedia() }.`data`.any { it.id == media.id })
            s.client.newCall(Request.Builder().url("${s.profile.apiBase}/media/${media.id}").build()).execute().use { assertEquals(200, it.code) }
            log("Upload/Bibliothek OK (${media.id})")

            apiCall { s.api.calendars.getCalendarStatus(a.id) }
            apiCall { s.api.jobs.listJobs() }
        } finally {
            // Ausgangszustand wiederherstellen
            runCatching { apiCall { s.api.contents.endAdhoc(a.id) } }
            scheduleId?.let { id -> runCatching { apiCall { s.api.schedules.deleteSchedule(id) } } }
            runCatching {
                val cur = apiCall { s.api.contents.getSlot(a.id, 2) }
                apiCall { s.api.contents.updateSlot(a.id, 2, original2.content, ifMatch = cur.etag) }
            }.onFailure { log("Ansicht 2 nicht zurückgesetzt: ${it.message}") }
            runCatching {
                val j = apiCall { s.api.displays.setActiveSlot(a.id, SetActiveSlotRequest(1)) }
                log("Rücksetzen auf Ansicht 1 → ${s.awaitJob(a.id, j.id)}")
            }.onFailure { log("Ansicht 1 nicht gesetzt: ${it.message}") }
            s.logout()
            s.close()
            dir.deleteRecursively()
        }
    }

    @Test fun t3_stufenViewUndSwitch() = runBlocking<Unit> {
        enabled()
        val v = session(); v.loginAs("view")
        val a = v.displayA()
        assertEquals("view", a.level.value)
        assertEquals(403, assertFailsWith<ApiException> { apiCall { v.api.displays.setActiveSlot(a.id, SetActiveSlotRequest(1)) } }.status)
        assertEquals(403, assertFailsWith<ApiException> { apiCall { v.api.contents.startAdhoc(a.id, StartAdhocRequest(TextContent(message = "x"), 15)) } }.status)
        v.logout(); v.close()

        val sw = session(); sw.loginAs("switch")
        val a2 = sw.displayA()
        assertEquals("switch", a2.level.value)
        val slot1 = apiCall { sw.api.contents.getSlot(a2.id, 1) }
        assertEquals(403, assertFailsWith<ApiException> { apiCall { sw.api.contents.updateSlot(a2.id, 1, slot1.content, ifMatch = slot1.etag) } }.status)
        sw.logout(); sw.close()

        val ad = session(); ad.loginAs("admin")
        assertEquals("edit", ad.displayA().level.value)
        assertEquals("admin", apiCall { ad.api.me.getMe() }.role.value)
        ad.logout(); ad.close()
        log("Stufen OK")
    }

    @Test fun t4_refreshRotationGnadenfristUndLogout() = runBlocking<Unit> {
        enabled()
        var ended = false
        val s = session(onEnded = { ended = true })
        s.loginAs("edit") // eigenes Login nur für diesen Test
        val first = s.tokens.load()!!
        val p1 = apiCall { s.api.auth.refreshToken(RefreshTokenRequest(first.refreshToken)) }
        // Gnadenfrist: alter Refresh-Token liefert innerhalb von 60 s dasselbe neue Paar
        val p2 = apiCall { s.api.auth.refreshToken(RefreshTokenRequest(first.refreshToken)) }
        assertEquals(p1.accessToken, p2.accessToken)
        assertEquals(p1.refreshToken, p2.refreshToken)
        s.tokens.save(p1.toTokens())
        assertNotNull(apiCall { s.api.me.getMe() })
        log("Refresh-Rotation + Gnadenfrist OK")

        // Logout widerruft: Danach führt der alte Token zu 401 unauthorized → Sitzung beendet
        val revoked = s.tokens.load()!!
        s.logout()
        s.tokens.save(revoked)
        val e = assertFailsWith<ApiException> { apiCall { s.api.me.getMe() } }
        assertEquals(401, e.status)
        assertTrue(ended, "onSessionEnded muss nach widerrufenem Token aufgerufen werden")
        s.close()
        log("Logout/Widerruf OK")
    }
}
