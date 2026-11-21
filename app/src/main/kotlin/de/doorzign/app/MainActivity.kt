package de.doorzign.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.doorzign.app.ui.AppRoot
import de.doorzign.app.ui.theme.DoorZignTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as DoorZignApp).container

    /** Wurde seit dem letzten onResume ein SSO-Callback zugestellt? (onNewIntent läuft vor onResume) */
    private var ssoCallbackSeen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { DoorZignTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        if (!ssoCallbackSeen) container.sessions.onResumedWithoutSsoCallback()
        ssoCallbackSeen = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** `doorzign://auth/callback` (SSO) sowie `doorzign://edge|cloud` (Verbindungscode als Link). */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "doorzign") return
        when (uri.host) {
            "auth" -> if (uri.path == "/callback") { ssoCallbackSeen = true; container.sessions.handleSsoCallback(uri) }
            // Einordnung je nach Zustand (Onboarding, Anmeldeseite, angemeldet) im SessionManager
            "edge", "cloud" -> container.sessions.handleLink(uri.toString())
        }
    }
}
