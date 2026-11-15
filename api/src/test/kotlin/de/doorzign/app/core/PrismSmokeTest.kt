package de.doorzign.app.core

import de.doorzign.app.api.model.ScheduleInput
import de.doorzign.app.api.model.SetActiveSlotRequest
import de.doorzign.app.api.model.StartAdhocRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Ruft jeden Endpunkt über den echten Session-Stack gegen Prism auf und deserialisiert die Fixtures.
 * Läuft nur mit `PRISM_URL=http://host.docker.internal:4010 ./build.sh :api:test`.
 */
class PrismSmokeTest {
    private val prism = System.getenv("PRISM_URL").orEmpty()

    @Test fun alleEndpunkteGegenMock() = runBlocking {
        assumeTrue("PRISM_URL nicht gesetzt", prism.isNotEmpty())
        val app = AppInfo("1.0.0")
        val probe = SystemProbe.probe(prism, Trust.System, app, apiPath = "")
        assertEquals(Compatibility.OK, Versions.check(probe.system.apiVersion, probe.system.minAppVersion, app.version))
        val profile = ConnectionProfile(SystemType.EDGE, probe.system.instanceId, probe.system.name, listOf(prism), apiPath = "")
        val session = Session(profile, InMemoryTokenStore(), app, null, onSessionEnded = {})
        val api = session.api

        apiCall { api.auth.getAuthProviders() }
        session.login("anna", "geheim", "Smoke-Test")
        assertTrue(session.isLoggedIn)
        apiCall { api.me.getMe() }

        val displays = apiCall { api.displays.listDisplays() }.`data`
        assertTrue(displays.isNotEmpty())
        val id = displays.first().id
        apiCall { api.displays.getDisplay(id) }
        val slots = apiCall { api.contents.listSlots(id) }.`data`
        assertEquals(3, slots.size)
        apiCall { api.contents.getSlot(id, 1) }
        apiCall { api.contents.updateSlot(id, 2, SlotContent.Text(title = "Hallo"), ifMatch = slots[1].etag) }
        apiCall { api.displays.setActiveSlot(id, SetActiveSlotRequest(2)) }
        apiCall { api.displays.identifyDisplay(id) }
        apiCall { api.displays.resendDisplay(id) }
        apiCall { api.contents.getAdhocWithHttpInfo(id) }
        apiCall { api.contents.startAdhoc(id, StartAdhocRequest(TextContent(message = "Heute geschlossen"), 60)) }
        apiCall { api.schedules.listSchedules(id) }
        apiCall { api.schedules.createSchedule(id, ScheduleInput(1, setOf(1, 2, 3), "08:00", "17:00", 50, true, "Werktags")) }
        apiCall { api.calendars.getCalendarStatus(id) }
        apiCall { api.jobs.listDisplayJobs(id, open = true) }
        apiCall { api.jobs.listJobs() }
        session.logout()
        session.close()
    }
}
