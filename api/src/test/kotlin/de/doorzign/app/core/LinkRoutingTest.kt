package de.doorzign.app.core

import de.doorzign.app.core.LinkRouting.Decision
import de.doorzign.app.core.LinkRouting.Where
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Verbindungslinks je nach Zustand (ui-spec §1 „Verbindungslinks“, decisions 45). */
class LinkRoutingTest {
    private val fp = "a".repeat(64)
    private fun edgeLink(instance: String) = "doorzign://edge?u=https://10.0.0.1:8443&fp=$fp&i=$instance"
    private val invalid = "doorzign://edge?u=http://10.0.0.1:8090&fp=$fp&i=x"
    private val edge = ConnectionProfile(SystemType.EDGE, "inst-1", "Rathaus", listOf("https://10.0.0.1:8443"), listOf(fp))
    private val cloud = ConnectionProfile(SystemType.CLOUD, "zw-1", "Zeitwart", listOf("https://Zeitwart.example/"))

    @Test fun onboardingNimmtAllesAuchUngueltiges() {
        assertEquals(Decision.ToOnboarding, LinkRouting.decide(edgeLink("inst-2"), Where.ONBOARDING, null))
        assertEquals(Decision.ToOnboarding, LinkRouting.decide(invalid, Where.ONBOARDING, null))
    }

    @Test fun anmeldeseiteGueltigerLinkWirdUebernommen() {
        assertEquals(Decision.DisconnectAndTake, LinkRouting.decide(edgeLink("inst-2"), Where.SIGN_IN_PAGE, edge))
        // auch das gleiche System: wie Onboarding direkt prüfen und übernehmen
        assertEquals(Decision.DisconnectAndTake, LinkRouting.decide(edgeLink("inst-1"), Where.SIGN_IN_PAGE, edge))
    }

    @Test fun anmeldeseiteUngueltigerLinkZeigtFehlerOhneAbmelden() {
        val d = LinkRouting.decide(invalid, Where.SIGN_IN_PAGE, edge)
        assertIs<Decision.ShowError>(d)
        assertEquals(PairingException.Reason.HTTPS_ONLY, d.error.reason)
    }

    @Test fun angemeldetAnderesSystemFragtNach() {
        assertEquals(Decision.ConfirmSwitch, LinkRouting.decide(edgeLink("inst-2"), Where.SIGNED_IN, edge))
        assertEquals(Decision.ConfirmSwitch, LinkRouting.decide(edgeLink("inst-1"), Where.SIGNED_IN, cloud))
    }

    @Test fun angemeldetGleichesSystemWirdVerworfen() {
        // gleiche instanceId genügt, auch mit anderer Adresse
        val other = "doorzign://edge?u=https://gw.local:9443&fp=$fp&i=inst-1"
        assertEquals(Decision.Drop, LinkRouting.decide(other, Where.SIGNED_IN, edge))
    }

    @Test fun angemeldetUngueltigerLinkWirdVerworfen() {
        assertEquals(Decision.Drop, LinkRouting.decide(invalid, Where.SIGNED_IN, edge))
    }

    /** `doorzign://cloud` ist überall gültig, auch solange der Cloud-QR-Knopf ausgeblendet ist (CLOUD_QR = false). */
    @Test fun cloudLinksSindUeberallGueltig() {
        val link = "doorzign://cloud?u=https%3A%2F%2Fandere.zeitwart.de"
        assertEquals(Decision.ToOnboarding, LinkRouting.decide(link, Where.ONBOARDING, null))
        assertEquals(Decision.DisconnectAndTake, LinkRouting.decide(link, Where.SIGN_IN_PAGE, cloud))
        assertEquals(Decision.ConfirmSwitch, LinkRouting.decide(link, Where.SIGNED_IN, edge))
        assertEquals(Decision.ConfirmSwitch, LinkRouting.decide(link, Where.SIGNED_IN, cloud))
        assertEquals(Decision.Drop, LinkRouting.decide("doorzign://cloud?u=https://zeitwart.example/", Where.SIGNED_IN, cloud))
    }

    @Test fun cloudGleicheBasisUrlNormalisiert() {
        assertTrue(LinkRouting.isSameSystem(PairingUri.Cloud("https://zeitwart.example", null), cloud))
        assertFalse(LinkRouting.isSameSystem(PairingUri.Cloud("https://andere.example", null), cloud))
        assertFalse(LinkRouting.isSameSystem(PairingUri.Cloud("https://10.0.0.1:8443", null), edge))
    }
}
