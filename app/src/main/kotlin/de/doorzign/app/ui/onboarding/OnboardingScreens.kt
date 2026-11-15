package de.doorzign.app.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.doorzign.app.R
import de.doorzign.app.core.Fingerprints
import de.doorzign.app.core.PairingUri
import de.doorzign.app.net.FoundGateway
import de.doorzign.app.ui.common.BusyButton
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.Texts
import de.doorzign.app.ui.common.appContainer
import de.doorzign.app.ui.common.containerViewModel

private object R0 {
    const val WELCOME = "welcome"
    const val EDGE = "edge"
    const val SCAN = "scan"
    const val SEARCH = "search"
    const val ADDRESS = "address"
    const val CLOUD = "cloud"
}

/** Onboarding: System wählen → QR / Suche / Adresse bzw. Instanz → (Fingerprint bestätigen) → Anmeldung. */
@Composable
fun OnboardingFlow() {
    val vm = containerViewModel { OnboardingViewModel(it) }
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions = appContainer().sessions
    val pending by sessions.pendingPairing.collectAsState()

    // Deep Link / QR aus einer anderen App übernehmen
    LaunchedEffect(pending) {
        pending?.let { sessions.pendingPairing.value = null; vm.onPairing(it) }
    }

    NavHost(nav, startDestination = R0.WELCOME) {
        composable(R0.WELCOME) { WelcomeScreen(nav, vm) }
        composable(R0.EDGE) { EdgeChoiceScreen(nav) }
        composable(R0.SCAN) {
            Step(nav, stringResource(R.string.edge_scan_title), state.error, vm::clearError) {
                Box(Modifier.fillMaxSize()) {
                    QrScanner(onCode = vm::onQr, modifier = Modifier.fillMaxSize())
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    Text(stringResource(R.string.edge_scan_hint), Modifier.align(Alignment.BottomCenter).padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                }
            }
        }
        composable(R0.SEARCH) { SearchScreen(nav, vm) }
        composable(R0.ADDRESS) { AddressScreen(nav, vm) }
        composable(R0.CLOUD) { CloudScreen(nav, vm) }
    }

    state.tofu?.let { t ->
        AlertDialog(
            onDismissRequest = vm::cancelTofu,
            title = { Text(stringResource(R.string.tofu_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.tofu_text, t.probe.system.name))
                    Text(Fingerprints.blocks(t.fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.tofu_compare), style = MaterialTheme.typography.bodySmall)
                    t.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmTofu, enabled = !state.busy) { Text(stringResource(R.string.tofu_trust)) } },
            dismissButton = { TextButton(onClick = vm::cancelTofu) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step(nav: NavController, title: String, error: String?, onDismissError: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = { onDismissError(); nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            error?.let { ErrorCard(it, Modifier.padding(16.dp)) }
            content()
        }
    }
}

@Composable
private fun WelcomeScreen(nav: NavController, vm: OnboardingViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.welcome_choose), style = MaterialTheme.typography.bodyLarge)
            ChoiceCard(Icons.Default.Router, stringResource(R.string.welcome_edge_title), stringResource(R.string.welcome_edge_text)) {
                vm.clearError(); nav.navigate(R0.EDGE)
            }
            ChoiceCard(Icons.Default.Cloud, stringResource(R.string.welcome_cloud_title), stringResource(R.string.welcome_cloud_text)) {
                vm.clearError(); nav.navigate(R0.CLOUD)
            }
            if (vm.mockAvailable) {
                ChoiceCard(Icons.Default.Science, stringResource(R.string.welcome_mock_title), stringResource(R.string.welcome_mock_text)) {
                    vm.connectMock()
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorCard(it) }
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EdgeChoiceScreen(nav: NavController) {
    Step(nav, stringResource(R.string.welcome_edge_title), null, {}) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(Icons.Default.QrCodeScanner, stringResource(R.string.edge_scan_title), stringResource(R.string.edge_scan_text)) { nav.navigate(R0.SCAN) }
            ChoiceCard(Icons.Default.Search, stringResource(R.string.edge_search_title), stringResource(R.string.edge_search_text)) { nav.navigate(R0.SEARCH) }
            ChoiceCard(Icons.Default.Edit, stringResource(R.string.edge_address_title), stringResource(R.string.edge_address_text)) { nav.navigate(R0.ADDRESS) }
        }
    }
}

@Composable
private fun SearchScreen(nav: NavController, vm: OnboardingViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nsd = appContainer().nsd
    var found by remember { mutableStateOf<List<FoundGateway>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { nsd.discover().collect { found = it } } catch (e: de.doorzign.app.net.DiscoveryFailed) { searchError = Texts.get(R.string.err_mdns, e.errorCode) }
    }
    Step(nav, stringResource(R.string.edge_search_title), state.error ?: searchError, vm::clearError) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (found.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.edge_search_running), textAlign = TextAlign.Center)
            }
        }
        LazyColumn {
            items(found, key = { it.serviceName }) { gw ->
                ListItem(
                    headlineContent = { Text(gw.title) },
                    supportingContent = { Text("${gw.host}:${gw.httpsPort}") },
                    leadingContent = { Icon(Icons.Default.Router, null) },
                    modifier = Modifier.clickable(enabled = !state.busy) { vm.probeEdgeAddress(gw.origin, gw) },
                )
            }
        }
    }
}

@Composable
private fun AddressScreen(nav: NavController, vm: OnboardingViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    Step(nav, stringResource(R.string.edge_address_title), state.error, vm::clearError) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                label = { Text(stringResource(R.string.edge_address_label)) },
                supportingText = { Text(stringResource(R.string.edge_address_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.probeEdgeAddress(address) }),
                modifier = Modifier.fillMaxWidth(),
            )
            BusyButton(stringResource(R.string.action_connect), state.busy, { vm.probeEdgeAddress(address) },
                Modifier.fillMaxWidth(), enabled = address.isNotBlank())
        }
    }
}

@Composable
private fun CloudScreen(nav: NavController, vm: OnboardingViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var instance by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    Step(nav, stringResource(R.string.welcome_cloud_title), state.error, vm::clearError) {
        if (scanning) {
            Box(Modifier.fillMaxSize()) {
                QrScanner(onCode = { scanning = false; vm.onQr(it) }, modifier = Modifier.fillMaxSize())
                TextButton(onClick = { scanning = false }, Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            return@Step
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = instance, onValueChange = { instance = it },
                label = { Text(stringResource(R.string.cloud_instance_label)) },
                supportingText = { Text(stringResource(R.string.cloud_instance_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.connectCloud(instance) }),
                modifier = Modifier.fillMaxWidth(),
            )
            BusyButton(stringResource(R.string.action_connect), state.busy, { vm.connectCloud(instance) },
                Modifier.fillMaxWidth(), enabled = instance.isNotBlank())
            if (PairingUri.CLOUD_QR) { // erst sichtbar, wenn Zeitwart einen Verbindungs-QR anbietet (ui-spec)
                TextButton(onClick = { scanning = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.cloud_scan))
                }
            }
        }
    }
}
