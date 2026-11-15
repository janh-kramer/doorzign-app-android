package de.doorzign.app.ui.common

import android.icu.text.MeasureFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/**
 * Wochentage, Dauer und relative Zeit aus der Gerätesprache statt aus Strings (docs/glossary.md).
 * Locale wird je Aufruf gelesen, damit ein Sprachwechsel sofort greift.
 */
object LocaleFormat {
    private val locale: Locale get() = Locale.getDefault()

    /** ISO-Wochentag (1 = Montag) → „Mo“ / „Mon“. */
    fun weekdayShort(isoDay: Int): String =
        DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.')

    /** Minuten → „1 Std., 30 Min.“ / „1 hr, 30 min“ (ohne Stunden-Anteil nur Minuten). */
    fun duration(minutes: Long): String {
        val f = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> f.format(Measure(m, MeasureUnit.MINUTE))
            m == 0L -> f.format(Measure(h, MeasureUnit.HOUR))
            else -> f.formatMeasures(Measure(h, MeasureUnit.HOUR), Measure(m, MeasureUnit.MINUTE))
        }
    }

    /** Zeitpunkt in der Vergangenheit → „gerade eben“/„now“, „vor 5 Minuten“/„5 minutes ago“ usw. */
    fun relative(t: Instant, now: Instant = Instant.now()): String {
        val f = RelativeDateTimeFormatter.getInstance(locale)
        val d = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            d.toMinutes() < 60 -> f.format(d.toMinutes().toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            d.toHours() < 48 -> f.format(d.toHours().toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.HOURS)
            else -> f.format(d.toDays().toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        }
    }
}
