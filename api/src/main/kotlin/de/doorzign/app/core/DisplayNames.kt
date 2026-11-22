package de.doorzign.app.core

import de.doorzign.app.api.model.Display

/**
 * Anzeigename eines Displays (ui-spec §2, decisions 48): Ist `name` leer oder nur Leerzeichen,
 * steht dort die `mac` so, wie das Backend sie liefert. Sortieren und Suchen verwenden diesen Namen;
 * die Suche findet zusätzlich immer die MAC.
 */
object DisplayNames {
    fun shown(name: String?, mac: String): String = if (name.isNullOrBlank()) mac else name

    fun shown(d: Display): String = shown(d.name, d.mac)

    /** Treffer bei leerer Suche, im angezeigten Namen, im Ort oder in der MAC. */
    fun matches(name: String?, mac: String, location: String?, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || shown(name, mac).contains(q, true) || location.orEmpty().contains(q, true) || mac.contains(q, true)
    }

    fun matches(d: Display, query: String): Boolean = matches(d.name, d.mac, d.location, query)
}

/** Kurzform für die UI. */
val Display.shownName: String get() = DisplayNames.shown(this)
