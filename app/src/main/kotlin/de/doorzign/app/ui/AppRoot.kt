package de.doorzign.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.doorzign.app.R
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
    // Verbindungslink auf ein anderes System, während man angemeldet ist (ui-spec §1, decisions 45): Bestätigung wie §8
    val link by sessions.linkSwitch.collectAsStateWithLifecycle()
    if (link != null && state is AppState.SignedIn) AlertDialog(
        onDismissRequest = sessions::cancelLinkSwitch,
        title = { Text(stringResource(R.string.settings_switch_title)) },
        text = { Text(stringResource(R.string.settings_switch_confirm)) },
        confirmButton = { TextButton(onClick = sessions::confirmLinkSwitch) { Text(stringResource(R.string.settings_switch_disconnect)) } },
        dismissButton = { TextButton(onClick = sessions::cancelLinkSwitch) { Text(stringResource(R.string.action_cancel)) } },
    )
}
