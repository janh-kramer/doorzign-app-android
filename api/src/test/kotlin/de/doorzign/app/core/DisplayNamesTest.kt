package de.doorzign.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayNamesTest {
    private val mac = "AA:BB:CC:DD:EE:01"

    @Test fun leererNameZeigtMac() {
        assertEquals(mac, DisplayNames.shown("", mac))
        assertEquals(mac, DisplayNames.shown(null, mac))
    }

    @Test fun nurLeerzeichenZeigtMac() {
        assertEquals(mac, DisplayNames.shown("   ", mac))
        assertEquals(mac, DisplayNames.shown(" \t ", mac))
    }

    @Test fun gesetzterNameBleibt() {
        assertEquals("Raum 1.23", DisplayNames.shown("Raum 1.23", mac))
    }

    @Test fun macUnveraendertWieVomBackend() {
        assertEquals("aa-bb-cc-dd-ee-01", DisplayNames.shown(" ", "aa-bb-cc-dd-ee-01"))
    }

    @Test fun sucheFindetMacImmer() {
        assertTrue(DisplayNames.matches("Raum 1.23", mac, null, "dd:ee"))
        assertTrue(DisplayNames.matches("", mac, null, "dd:ee"))
        assertTrue(DisplayNames.matches("Raum 1.23", mac, null, "aa:bb:cc:dd:ee:01"))
    }

    @Test fun sucheNachAngezeigtemNamenUndOrt() {
        assertTrue(DisplayNames.matches("Raum 1.23", mac, "Foyer", "raum"))
        assertTrue(DisplayNames.matches("Raum 1.23", mac, "Foyer", "foy"))
        assertTrue(DisplayNames.matches("  ", mac, null, ""))
        assertFalse(DisplayNames.matches("Raum 1.23", mac, null, "Küche"))
    }

    @Test fun sortierungNachAngezeigtemNamen() {
        val list = listOf("Zimmer" to "AA:01", " " to "BB:02", "Aula" to "CC:03")
            .sortedBy { DisplayNames.shown(it.first, it.second) }
            .map { DisplayNames.shown(it.first, it.second) }
        assertEquals(listOf("Aula", "BB:02", "Zimmer"), list)
    }
}
