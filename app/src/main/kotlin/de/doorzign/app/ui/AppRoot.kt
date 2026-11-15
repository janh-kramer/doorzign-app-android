package de.doorzign.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.doorzign.app.data.AppState
import de.doorzign.app.ui.common.appContainer
import de.doorzign.app.ui.login.LoginScreen
import de.doorzign.app.ui.main.MainNav
import de.doorzign.app.ui.onboarding.OnboardingFlow

/** Oberste Ebene: Onboarding, Anmeldung oder Hauptbereich – je nach Zustand der Verbindung. */
@Composable
fun AppRoot() {
    val sessions = appContainer().sessions
    val state by sessions.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AppState.Onboarding -> OnboardingFlow()
        is AppState.SignedOut -> LoginScreen(s.notice)
        // Schlüssel = instanceId: ein neues System bekommt einen frischen Navigations- und ViewModel-Zustand
        is AppState.SignedIn -> androidx.compose.runtime.key(s.conn.profile.instanceId) { MainNav(s.conn) }
    }
}
