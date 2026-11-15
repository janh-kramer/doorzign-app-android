package de.doorzign.app.core

import de.doorzign.app.api.model.Display
import de.doorzign.app.api.model.RefreshTokenRequest
import de.doorzign.app.api.model.ScheduleInput
import de.doorzign.app.api.model.SetActiveSlotRequest
import de.doorzign.app.api.model.StartAdhocRequest
import de.doorzign.app.api.model.CheckCalendarUrlRequest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue

/**
 * Ende-zu-Ende gegen Zeitwart Cloud (lokales Sail, ohne TLS). Eigenheiten laut docs/parity.md:
 * numerische IDs, signal/firmware/lastError null, resend 0–3 Jobs, kein GET /media (Liste),
 * Jobs werden lokal nie abgeschlossen, transferred bleibt nach PUT false.
 * Start: `E2E_CLOUD_URL=http://host.docker.internal ./build.sh :api:test --tests '*ZeitwartE2E*'`.
 * Endzustand: A aktiv 1, Ansichten text/text/deactivated, keine Ad-hoc, keine Testzeitpläne, Logout.
 */
class ZeitwartE2ETest {
    private val url = System.getenv("E2E_CLOUD_URL").orEmpty()
    private val app = AppInfo("1.0.0")
    private val password = "conformance"

    private fun enabled() = assumeTrue("E2E_CLOUD_URL nicht gesetzt", url.isNotEmpty())

    private fun session(onEnded: () -> Unit = {}): Session {
        val profile = ConnectionProfile(SystemType.CLOUD, "e2e-zw", "Zeitwart E2E", listOf(url))
        return Session(profile, InMemoryTokenStore(), app, null, onSessionEnded = onEnded)
    }

    private suspend fun Session.loginAs(role: String) = login("mobile-conformance-$role", password, "Android-E2E $role")
    private suspend fun Session.display(name: String): Display? =
        apiCall { api.displays.listDisplays() }.`data`.firstOrNull { it.name == name }

    private fun log(s: String) = println("[ZW] $s")
    private fun numeric(id: String) = assertTrue(id.all { it.isDigit() }, "ID '$id' ist nicht numerisch")

    private fun Session.get(path: String, etag: String? = null) =
        client.newCall(Request.Builder().url("${profile.apiBase}$path").apply { etag?.let { header("If-None-Match", it) } }.build()).execute()

    @Test fun t1_systemOhneTls() = runBlocking<Unit> {
        enabled()
        val sys = SystemProbe.probe(url, Trust.System, app).system
        assertEquals("cloud", sys.systemType.value)
        assertFalse("media.library" in sys.features, "Zeitwart darf media.library nicht melden")
        log("System OK: ${sys.name} ${sys.apiVersion}/${sys.backendVersion}, features=${sys.features}, mediaMaxBytes=${sys.mediaMaxBytes}")
    }

    @Test fun t2_editAblauf() = runBlocking<Unit> {
        enabled()
        val s = session()
        s.loginAs("edit")
        val features = SystemProbe.probe(url, Trust.System, app).system.features
        val a = assertNotNull(s.display("Konformität A"))
        numeric(a.id)
        assertEquals("edit", a.level.value)
        assertNull(a.signal); assertNull(a.firmware); assertNull(a.lastError)
        assertNull(s.display("Konformität B"), "B darf für edit nicht sichtbar sein")
        log("A=${a.id}, aktiv=${a.activeSlot}, slots=${a.slots.map { "${it.slot}:${it.type}:${it.previewEtag}" }}")

        val original2 = apiCall { s.api.contents.getSlot(a.id, 2) }
        var scheduleId: String? = null
        try {
            // Speichern mit If-Match; transferred bleibt lokal false; veraltetes ETag → 412
            val saved = apiCall { s.api.contents.updateSlot(a.id, 2, SlotContent.Text(title = "E2E Android", message = "Test"), ifMatch = original2.etag) }
            assertNotEquals(original2.etag, saved.etag)
            assertFalse(saved.transferred)
            assertEquals(412, assertFailsWith<ApiException> {
                apiCall { s.api.contents.updateSlot(a.id, 2, SlotContent.Text(title = "veraltet"), ifMatch = original2.etag) }
            }.status)
            log("If-Match/412 OK, transferred=${saved.transferred}")

            // Live-Vorschau; Überlänge → 422 fields.title
            val png = s.renderPreview(a.id, 2, SlotContent.Text(title = "Vorschau"))
            assertTrue(png.size > 8 && png[1] == 'P'.code.toByte())
            val long = assertFailsWith<ApiException> { s.renderPreview(a.id, 2, SlotContent.Text(title = "x".repeat(51))) }
            assertEquals(422, long.status)
            assertTrue("title" in long.fields || "content.title" in long.fields, "fields=${long.fields}")
            log("Vorschau OK, Überlänge → 422 ${long.fields.keys}")

            // preview.png: Text mit ETag → bedingt 304; deaktivierte Ansicht 3 → previewEtag null und 404
            val etag1 = assertNotNull(apiCall { s.api.contents.getSlot(a.id, 1) }.previewEtag)
            s.get("/displays/${a.id}/slots/1/preview.png").use { assertEquals(200, it.code); assertEquals(etag1, it.header("ETag")) }
            s.get("/displays/${a.id}/slots/1/preview.png", etag1).use { assertEquals(304, it.networkResponse?.code ?: it.code) }
            assertNull(apiCall { s.api.contents.getSlot(a.id, 3) }.previewEtag)
            s.get("/displays/${a.id}/slots/3/preview.png").use { assertEquals(404, it.code) }
            log("preview.png ETag/304, deaktiviert → 404 OK")

            // Umschalten erzeugt offenen Job (lokal ohne Gerät – nicht auf done warten)
            val job = apiCall { s.api.displays.setActiveSlot(a.id, SetActiveSlotRequest(2)) }
            numeric(job.id)
            assertTrue(JobStates.isOpen(job.status.value), "Status ${job.status.value}")
            val jobs = apiCall { s.api.jobs.listDisplayJobs(a.id) }.`data`
            assertTrue(jobs.any { it.id == job.id } && jobs.size <= 100)
            apiCall { s.api.displays.identifyDisplay(a.id) }
            val resent = apiCall { s.api.displays.resendDisplay(a.id) }.`data`
            assertTrue(resent.size in 0..3, "resend lieferte ${resent.size}")
            log("Job ${job.id} ${job.status.value}, Display-Jobs=${jobs.size}, resend=${resent.size}")

            // Ad-hoc
            val ad = apiCall { s.api.contents.startAdhoc(a.id, StartAdhocRequest(TextContent(message = "E2E Android"), 15)) }
            assertEquals("E2E Android", ad.content.message)
            assertEquals(200, apiCall { s.api.contents.getAdhocWithHttpInfo(a.id) }.statusCode)
            apiCall { s.api.contents.endAdhoc(a.id) }
            assertEquals(204, apiCall { s.api.contents.getAdhocWithHttpInfo(a.id) }.statusCode)
            log("Ad-hoc OK")

            // Zeitpläne
            val created = apiCall { s.api.schedules.createSchedule(a.id, ScheduleInput(1, setOf(6, 7), "22:00", "06:00", 10, false, "E2E Android")) }
            scheduleId = created.id
            numeric(created.id)
            val upd = apiCall { s.api.schedules.updateSchedule(created.id, ScheduleInput(1, setOf(6), "22:00", "06:00", 11, false, "E2E Android"), ifMatch = created.etag) }
            assertEquals(11, upd.priority)
            assertEquals(412, assertFailsWith<ApiException> {
                apiCall { s.api.schedules.updateSchedule(created.id, ScheduleInput(1, setOf(6), "22:00", "06:00", 12, false), ifMatch = created.etag) }
            }.status)
            apiCall { s.api.schedules.deleteSchedule(created.id) }
            scheduleId = null
            log("Zeitpläne OK")

            // Upload nur mit media.upload; keine Bibliothek (GET /media gibt es nicht)
            if ("media.upload" in features) {
                val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
                val media = s.uploadMedia(bytes, "image/png", "e2e-android.png")
                // Medien-IDs sind bei Zeitwart UUIDs, nicht numerisch – die App behandelt IDs ohnehin opak
                s.get("/media/${media.id}").use { assertEquals(200, it.code) }
                log("Upload OK (${media.id})")
            } else log("Upload übersprungen (kein media.upload)")

            apiCall { s.api.jobs.listJobs() }
        } finally {
            runCatching { apiCall { s.api.contents.endAdhoc(a.id) } }
            scheduleId?.let { id -> runCatching { apiCall { s.api.schedules.deleteSchedule(id) } } }
            runCatching {
                val cur = apiCall { s.api.contents.getSlot(a.id, 2) }
                apiCall { s.api.contents.updateSlot(a.id, 2, original2.content, ifMatch = cur.etag) }
            }.onFailure { log("Ansicht 2 nicht zurückgesetzt: ${it.message}") }
            runCatching { apiCall { s.api.displays.setActiveSlot(a.id, SetActiveSlotRequest(1)) } }
                .onFailure { log("Ansicht 1 nicht gesetzt: ${it.message}") }
            runCatching {
                val end = apiCall { s.api.displays.getDisplay(a.id) }
                log("Endzustand A: aktiv=${end.activeSlot}, slots=${end.slots.map { "${it.slot}:${it.type}" }}, adhocUntil=${end.adhocUntil}, " +
                    "Zeitpläne=${apiCall { s.api.schedules.listSchedules(a.id) }.`data`.size}")
            }
            s.logout(); s.close()
        }
    }

    @Test fun t3_stufen() = runBlocking<Unit> {
        enabled()
        val v = session(); v.loginAs("view")
        val a = assertNotNull(v.display("Konformität A"))
        assertEquals("view", a.level.value)
        assertEquals(403, assertFailsWith<ApiException> { apiCall { v.api.displays.setActiveSlot(a.id, SetActiveSlotRequest(1)) } }.status)
        assertEquals(403, assertFailsWith<ApiException> { apiCall { v.api.contents.startAdhoc(a.id, StartAdhocRequest(TextContent(message = "x"), 15)) } }.status)
        v.logout(); v.close()

        val sw = session(); sw.loginAs("switch")
        val a2 = assertNotNull(sw.display("Konformität A"))
        assertEquals("switch", a2.level.value)
        val slot1 = apiCall { sw.api.contents.getSlot(a2.id, 1) }
        assertEquals(403, assertFailsWith<ApiException> { apiCall { sw.api.contents.updateSlot(a2.id, 1, slot1.content, ifMatch = slot1.etag) } }.status)
        sw.logout(); sw.close()

        val ad = session(); ad.loginAs("admin")
        assertEquals("admin", apiCall { ad.api.me.getMe() }.role.value)
        assertNotNull(ad.display("Konformität B"), "admin muss B sehen")
        ad.logout(); ad.close()
        log("Stufen OK")
    }

    @Test fun t4_kalenderNurLesend() = runBlocking<Unit> {
        enabled()
        val s = session(); s.loginAs("admin")
        try {
            val c = assertNotNull(s.display("Konformität C"), "C nicht sichtbar für admin")
            assertTrue(c.license.calendar)
            val slot = apiCall { s.api.contents.getSlot(c.id, 1) }
            val cal = assertIs<SlotContent.Calendar>(slot.content)
            assertEquals(SlotContent.Calendar.Source.ICAL, cal.source)
            val st = apiCall { s.api.calendars.getCalendarStatus(c.id, 1) }
            assertTrue(st.licensed && st.configured)
            log("Kalender C: occupied=${st.occupied}, eventsToday=${st.eventsToday?.size}, lastSuccess=${st.lastSuccessAt}, error=${st.error}")
            val etag = assertNotNull(slot.previewEtag)
            s.get("/displays/${c.id}/slots/1/preview.png").use { assertEquals(200, it.code) }
            s.get("/displays/${c.id}/slots/1/preview.png", etag).use { log("preview.png bedingt → ${it.networkResponse?.code ?: it.code}") }
            val chk = apiCall { s.api.calendars.checkCalendarUrl(c.id, CheckCalendarUrlRequest(URI("http://localhost/mobile-conformance/calendar.ics"))) }
            assertTrue(chk.ok, "calendar/check: ${chk.error}")
            log("calendar/check OK, events=${chk.eventCount}")
        } finally { s.logout(); s.close() }
    }

    @Test fun t5_refreshUndLogout() = runBlocking<Unit> {
        enabled()
        var ended = false
        val s = session(onEnded = { ended = true })
        s.loginAs("edit")
        val first = s.tokens.load()!!
        val p1 = apiCall { s.api.auth.refreshToken(RefreshTokenRequest(first.refreshToken)) }
        val p2 = apiCall { s.api.auth.refreshToken(RefreshTokenRequest(first.refreshToken)) }
        assertEquals(p1.refreshToken, p2.refreshToken, "Gnadenfrist muss dasselbe Paar liefern")
        s.tokens.save(p1.toTokens())
        assertNotNull(apiCall { s.api.me.getMe() })
        log("Refresh-Rotation + Gnadenfrist OK")
        val revoked = s.tokens.load()!!
        s.logout()
        s.tokens.save(revoked)
        assertEquals(401, assertFailsWith<ApiException> { apiCall { s.api.me.getMe() } }.status)
        assertTrue(ended)
        s.close()
        log("Logout/Widerruf OK")
    }
}
