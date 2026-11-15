package de.doorzign.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import de.doorzign.app.core.AppInfo
import de.doorzign.app.data.KeystoreTokenStore
import de.doorzign.app.data.PendingSsoStore
import de.doorzign.app.data.ProfileStore
import de.doorzign.app.data.SessionManager
import de.doorzign.app.net.NsdDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Einfache manuelle Abhängigkeiten – genau eine Instanz pro Prozess. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val appInfo = AppInfo(version = BuildConfig.VERSION_NAME, languageOf = { if (java.util.Locale.getDefault().language == "de") "de" else "en" })
    val sessions = SessionManager(
        context = context,
        tokens = KeystoreTokenStore(context),
        profiles = ProfileStore(context),
        ssoStore = PendingSsoStore(context),
        appInfo = appInfo,
        scope = appScope,
    )
    val nsd = NsdDiscovery(context)

    /** Vorschlag für den Gerätenamen (änderbar in der Anmeldung). */
    val defaultDeviceName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")
}
