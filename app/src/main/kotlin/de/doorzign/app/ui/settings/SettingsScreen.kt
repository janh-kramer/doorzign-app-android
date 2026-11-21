package de.doorzign.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.doorzign.app.R
import de.doorzign.app.api.model.Me
import de.doorzign.app.core.Compatibility
import de.doorzign.app.core.SystemType
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.appContainer
import kotlinx.coroutines.launch

/** Verbunden mit, Benutzer, Abmelden / System wechseln, App-Version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(conn: Connection) {
    val c = appContainer()
    val system by c.sessions.system.collectAsStateWithLifecycle()
    val compat by c.sessions.compatibility.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var me by remember(conn) { mutableStateOf<Me?>(null) }
    LaunchedEffect(conn) { me = runCatching { apiCall { conn.api.me.getMe() } }.getOrNull() }
    LaunchedEffect(Unit) { c.sessions.refreshSystem() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (compat != Compatibility.OK) ErrorCard(stringResource(
                if (compat == Compatibility.UPDATE_APP) R.string.compat_update else R.string.compat_incompatible), Modifier.padding(16.dp))
            Section(stringResource(R.string.settings_connected))
            ListItem(headlineContent = { Text(system?.name ?: conn.profile.name) },
                supportingContent = { Text(stringResource(if (conn.profile.systemType == SystemType.EDGE) R.string.system_edge else R.string.system_cloud)) })
            ListItem(headlineContent = { Text(stringResource(R.string.settings_address)) }, supportingContent = { Text(conn.profile.primaryOrigin) })
            ListItem(headlineContent = { Text(stringResource(R.string.settings_api_version)) },
                supportingContent = { Text(listOfNotNull(system?.apiVersion, system?.backendVersion?.let { stringResource(R.string.settings_backend, it) }).joinToString(" · ")) })
            HorizontalDivider()
            Section(stringResource(R.string.settings_user))
            ListItem(headlineContent = { Text(me?.displayName ?: "…") },
                supportingContent = { Text(listOfNotNull(me?.username, me?.role?.let {
                    stringResource(if (it == Me.Role.ADMIN) R.string.role_admin else R.string.role_user) }).joinToString(" · ")) })
            HorizontalDivider()
            Column(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = { confirm = "logout" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_logout)) }
                Button(onClick = { confirm = "switch" }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.settings_switch))
                }
            }
            HorizontalDivider()
            ListItem(headlineContent = { Text(stringResource(R.string.settings_app_version)) }, supportingContent = { Text(c.appInfo.version) })
            ListItem(headlineContent = { Text(stringResource(R.string.settings_imprint)) },
                modifier = Modifier.clickable { LegalLinks.open(context, LegalLinks.IMPRINT) })
            ListItem(headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                modifier = Modifier.clickable { LegalLinks.open(context, LegalLinks.PRIVACY) })
        }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (what == "logout") R.string.settings_logout_title else R.string.settings_switch_title)) },
            text = { Text(stringResource(R.string.settings_switch_confirm)) },
            // Abmelden und Verbindung trennen destruktiv wie iOS (Glossar)
            confirmButton = {
                TextButton(onClick = { confirm = null; scope.launch { c.sessions.disconnect() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(if (what == "logout") R.string.settings_logout else R.string.settings_switch_disconnect))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
