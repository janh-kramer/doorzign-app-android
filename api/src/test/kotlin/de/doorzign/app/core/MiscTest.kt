package de.doorzign.app.core

import de.doorzign.app.api.infrastructure.Serializer
import de.doorzign.app.api.model.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MiscTest {
    @Test fun pkceRfc7636Testvektor() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val p = Pkce.create()
        assertTrue(p.verifier.length >= 43)
    }

    @Test fun versionen() {
        assertEquals(Compatibility.OK, Versions.check("1.3", "1.0.0", "1.0.0"))
        assertEquals(Compatibility.UPDATE_APP, Versions.check("2.0", "1.0.0", "1.0.0"))
        assertEquals(Compatibility.UPDATE_APP, Versions.check("1.0", "1.2.0", "1.1.9"))
        assertEquals(Compatibility.INCOMPATIBLE, Versions.check("0.9", "1.0.0", "1.0.0"))
        assertTrue(Versions.compare("1.2.10", "1.2.9") > 0)
    }

    @Test fun statusEinstufung() {
        assertEquals(SignalQuality.GOOD, DisplayStatus.signal(-65))
        assertEquals(SignalQuality.MEDIUM, DisplayStatus.signal(-80))
        assertEquals(SignalQuality.WEAK, DisplayStatus.signal(-81))
        assertEquals(BatteryState.CRITICAL, DisplayStatus.battery(14))
        assertEquals(BatteryState.LOW, DisplayStatus.battery(29))
        assertEquals(BatteryState.UNKNOWN, DisplayStatus.battery(null))
    }

    @Test fun slotMitSealedContentUndUnbekanntenFeldern() {
        val json = """{"slot":1,"transferred":true,"editable":true,"etag":"e1","neu":42,
            "content":{"type":"calendar","source":"ical","url":"https://x/cal.ics","view":{"layout":"list"}}}"""
        val s = Serializer.kotlinxSerializationJson.decodeFromString<Slot>(json)
        val c = assertIs<SlotContent.Calendar>(s.content)
        assertEquals(SlotContent.Calendar.Source.ICAL, c.source)
        assertEquals("e1", s.etag)
    }

    @Test fun textContentWirdMitTypSerialisiert() {
        val out = Serializer.kotlinxSerializationJson.encodeToString(SlotContent.serializer(), SlotContent.Text(title = "Hallo"))
        assertTrue(out.contains("\"type\":\"text\""), out)
        val back = Serializer.kotlinxSerializationJson.decodeFromString<SlotContent>("""{"type":"deactivated"}""")
        assertEquals(SlotContent.Deactivated, back)
    }

    @Test fun fehlerantwortWirdGeparst() {
        val e = ApiException.fromBody(422, """{"error":{"code":"validation","message":"Ungültig","fields":{"title":["Zu lang"]}}}""")
        assertEquals("validation", e.code)
        assertEquals(listOf("Zu lang"), e.fields["title"])
        val unknown = ApiException.fromBody(500, "<html>")
        assertEquals("internal", unknown.code)
    }

    /** Edge-Web erlaubt längere Texte als der Vertrag – Lesen darf daran nicht scheitern. */
    @Test fun ueberlangerTextWirdAngenommen() {
        val long = "x".repeat(200)
        val json = """{"slot":2,"transferred":true,"editable":true,"etag":"\"e\"","previewEtag":null,"content":{"type":"text","title":"${"t".repeat(120)}","headline":"${"h".repeat(160)}","subHeadline":"$long","message":"$long"}}"""
        val s = Serializer.kotlinxSerializationJson.decodeFromString<Slot>(json)
        val t = assertIs<SlotContent.Text>(s.content)
        assertEquals(120, t.title.length)
        assertEquals(setOf("title", "headline", "subHeadline", "message"), TextLimits.exceeded(t).keys)
        assertTrue(TextLimits.exceeded(SlotContent.Text(title = "x".repeat(50))).isEmpty())
    }
}

class ScheduleRulesTest {
    @Test fun gueltigerPlan() = assertTrue(ScheduleRules.validate(ScheduleRules.Draft()).isEmpty())

    @Test fun fehler() {
        val e = ScheduleRules.validate(ScheduleRules.Draft(weekdays = emptySet(), start = "8:00", end = "17:00", priority = "101",
            validFrom = "2026-12-01", validUntil = "2026-11-01"))
        assertEquals(setOf("weekdays", "start", "priority", "validUntil"), e.keys)
        assertEquals("end", ScheduleRules.validate(ScheduleRules.Draft(start = "10:00", end = "10:00")).keys.single())
    }

    @Test fun ueberMitternacht() {
        assertTrue(ScheduleRules.overnight(ScheduleRules.Draft(start = "22:00", end = "06:00")))
        assertTrue(!ScheduleRules.overnight(ScheduleRules.Draft(start = "06:00", end = "22:00")))
    }
}
