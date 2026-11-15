package de.doorzign.app.data

import android.content.Context

/** PKCE-Daten eines laufenden SSO-Vorgangs – überlebt den Prozessneustart während des Browser-Logins. */
class PendingSsoStore(context: Context) {
    private val prefs = context.getSharedPreferences("dz_sso", Context.MODE_PRIVATE)

    data class Pending(val state: String, val verifier: String, val instanceId: String, val startedAt: Long)

    fun save(p: Pending) {
        prefs.edit().putString("state", p.state).putString("verifier", p.verifier)
            .putString("instance", p.instanceId).putLong("at", p.startedAt).commit()
    }

    /** Liefert und löscht den Vorgang (Einmalverwendung). */
    fun take(): Pending? {
        val state = prefs.getString("state", null) ?: return null
        val p = Pending(state, prefs.getString("verifier", "")!!, prefs.getString("instance", "")!!, prefs.getLong("at", 0))
        prefs.edit().clear().commit()
        return p
    }

    fun clear() { prefs.edit().clear().commit() }
}
