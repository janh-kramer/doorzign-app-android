package de.doorzign.app.ui.login

import de.doorzign.app.ui.common.Texts

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.doorzign.app.AppContainer
import de.doorzign.app.R
import de.doorzign.app.core.SystemType
import de.doorzign.app.api.model.GetAuthProviders200Response
import de.doorzign.app.api.model.SsoProvider
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.SsoStatus
import de.doorzign.app.ui.common.BusyButton
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.Loadable
import de.doorzign.app.ui.common.appContainer
import de.doorzign.app.ui.common.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    val providers = MutableStateFlow<Loadable<GetAuthProviders200Response>>(Loadable.Loading)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val fieldErrors = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    init { loadProviders() }

    fun loadProviders() {
        val conn = c.sessions.connection ?: return
        providers.value = Loadable.Loading
        viewModelScope.launch {
            providers.value = try {
                Loadable.Ready(apiCall { conn.api.auth.getAuthProviders() })
            } catch (e: ApiException) {
                Loadable.Failed(Texts.error(e))
            }
        }
    }

    fun login(username: String, password: String, deviceName: String, provider: String?) {
        if (busy.value) return
        busy.value = true; error.value = null; fieldErrors.value = emptyMap()
        viewModelScope.launch {
            try {
                c.sessions.login(username.trim(), password, deviceName.trim().take(100), provider)
            } catch (e: ApiException) {
                // 401 beim Passwort-Login heißt: Zugangsdaten falsch (glossary.md), nicht „erneut anmelden“
                error.value = if (e.code == "unauthorized") Texts.get(R.string.login_wrong_credentials) else Texts.error(e)
                fieldErrors.value = e.fields
            } finally {
                busy.value = false
            }
        }
    }

    fun disconnect() = viewModelScope.launch { c.sessions.disconnect() }
    fun clearError() = error.update { null }
}

/** Anmeldung: Systemname als Überschrift, Benutzername/Passwort, darunter je Provider ein Button. */
@Composable
fun LoginScreen(notice: String?) {
    val c = appContainer()
    val vm = containerViewModel(key = "login-" + c.sessions.connection?.profile?.instanceId) { LoginViewModel(it) }
    val system by c.sessions.system.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fields by vm.fieldErrors.collectAsStateWithLifecycle()
    val sso by c.sessions.sso.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(c.defaultDeviceName) }
    var passwordProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSwitch by rememberSaveable { mutableStateOf(false) }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(system?.name ?: c.sessions.connection?.profile?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge)
            notice?.let { ErrorCard(it) }
            error?.let { ErrorCard(it) }
            (sso as? SsoStatus.Failed)?.let { ErrorCard(it.message) }
            if (sso is SsoStatus.Cancelled) Text(stringResource(R.string.sso_cancelled), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sso is SsoStatus.Working) LinearProgressIndicator(Modifier.fillMaxWidth())

            when (val p = providers) {
                Loadable.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Loadable.Failed -> ErrorCard(p.message, onRetry = vm::loadProviders)
                is Loadable.Ready -> {
                    val pwProvider: SsoProvider? = p.value.sso.firstOrNull { it.id == passwordProviderId }
                    if (p.value.password || pwProvider != null) {
                        pwProvider?.let { Text(stringResource(R.string.login_with, it.name), style = MaterialTheme.typography.titleMedium) }
                        val submit = { vm.login(username, password, deviceName, pwProvider?.id) }
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.login_username)) }, singleLine = true,
                            isError = fields["username"] != null,
                            supportingText = fields["username"]?.let { { Text(it.joinToString()) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.login_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            isError = fields["password"] != null,
                            supportingText = fields["password"]?.let { { Text(it.joinToString()) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }))
                        OutlinedTextField(deviceName, { deviceName = it.take(100) }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.login_device_name)) }, singleLine = true,
                            supportingText = { Text(stringResource(
                                if (c.sessions.connection?.profile?.systemType == SystemType.CLOUD) R.string.login_device_help_cloud
                                else R.string.login_device_help_edge)) })
                        BusyButton(stringResource(R.string.login_submit), busy, submit, Modifier.fillMaxWidth(),
                            enabled = username.isNotBlank() && password.isNotEmpty() && deviceName.isNotBlank())
                        if (pwProvider != null && p.value.password) {
                            TextButton(onClick = { passwordProviderId = null }, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.login_local_account))
                            }
                        }
                    }
                    val others = p.value.sso.filter { it.id != passwordProviderId }
                    if (others.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        others.forEach { prov ->
                            OutlinedButton(
                                onClick = {
                                    when (prov.flow) {
                                        SsoProvider.Flow.BROWSER -> (context as? Activity)?.let { c.sessions.startSso(it, prov, deviceName) }
                                        SsoProvider.Flow.PASSWORD -> passwordProviderId = prov.id
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && sso !is SsoStatus.Working,
                            ) { Text(stringResource(R.string.login_with, prov.name)) }
                        }
                    }
                    if (!p.value.password && p.value.sso.isEmpty()) ErrorCard(stringResource(R.string.login_no_methods))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { confirmSwitch = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_switch)) }
        }
    }

    if (confirmSwitch) {
        AlertDialog(
            onDismissRequest = { confirmSwitch = false },
            title = { Text(stringResource(R.string.settings_switch_title)) },
            text = { Text(stringResource(R.string.settings_switch_confirm)) },
            confirmButton = { TextButton(onClick = { confirmSwitch = false; scope.launch { vm.disconnect() } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.settings_switch_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
