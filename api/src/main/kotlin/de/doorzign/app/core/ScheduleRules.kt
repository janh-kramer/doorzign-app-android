package de.doorzign.app.core

import java.time.LocalDate

/** Validierung von Zeitplänen laut ui-spec §6 (Backend prüft zusätzlich, Fehler kommen dann als 422). */
object ScheduleRules {
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    data class Draft(
        val name: String = "",
        val slot: Int = 1,
        val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
        val start: String = "08:00",
        val end: String = "17:00",
        val validFrom: String = "",
        val validUntil: String = "",
        val priority: String = "50",
        val enabled: Boolean = true,
    )

    enum class Error { NO_WEEKDAY, TIME_FORMAT, START_EQUALS_END, PRIORITY_RANGE, DATE_FORMAT, UNTIL_BEFORE_FROM }

    /** Feldname (wie im Vertrag) → Fehler. Leer = gültig. Texte liefert die App. */
    fun validate(d: Draft): Map<String, Error> {
        val e = linkedMapOf<String, Error>()
        if (d.weekdays.isEmpty()) e["weekdays"] = Error.NO_WEEKDAY
        if (!TIME.matches(d.start)) e["start"] = Error.TIME_FORMAT
        if (!TIME.matches(d.end)) e["end"] = Error.TIME_FORMAT
        if ("start" !in e && "end" !in e && d.start == d.end) e["end"] = Error.START_EQUALS_END
        val p = d.priority.toIntOrNull()
        if (p == null || p !in 0..100) e["priority"] = Error.PRIORITY_RANGE
        val from = d.validFrom.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: run { e["validFrom"] = Error.DATE_FORMAT; null } }
        val until = d.validUntil.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: run { e["validUntil"] = Error.DATE_FORMAT; null } }
        if (from != null && until != null && until.isBefore(from)) e["validUntil"] = Error.UNTIL_BEFORE_FROM
        return e
    }

    /** Ende vor Start = über Mitternacht. */
    fun overnight(d: Draft): Boolean = TIME.matches(d.start) && TIME.matches(d.end) && d.end < d.start
}
