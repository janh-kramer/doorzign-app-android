package de.doorzign.app.data

import android.content.Context
import de.doorzign.app.api.infrastructure.Serializer
import de.doorzign.app.api.model.SystemInfo
import de.doorzign.app.core.ConnectionProfile

/** Das aktive Verbindungsprofil und die zuletzt geladene Systembeschreibung (für Features ohne Netz). */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("dz_profile", Context.MODE_PRIVATE)
    private val json = Serializer.kotlinxSerializationJson

    fun load(): ConnectionProfile? = prefs.getString("profile", null)?.let {
        runCatching { json.decodeFromString(ConnectionProfile.serializer(), it) }.getOrNull()
    }

    fun save(profile: ConnectionProfile) {
        prefs.edit().putString("profile", json.encodeToString(ConnectionProfile.serializer(), profile)).commit()
    }

    fun loadSystem(): SystemInfo? = prefs.getString("system", null)?.let {
        runCatching { json.decodeFromString(SystemInfo.serializer(), it) }.getOrNull()
    }

    fun saveSystem(system: SystemInfo) {
        prefs.edit().putString("system", json.encodeToString(SystemInfo.serializer(), system)).apply()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
