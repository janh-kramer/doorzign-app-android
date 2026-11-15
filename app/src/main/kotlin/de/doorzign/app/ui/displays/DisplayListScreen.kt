package de.doorzign.app.ui.displays

import de.doorzign.app.ui.common.Texts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import de.doorzign.app.R
import de.doorzign.app.api.model.Display
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.BatteryState
import de.doorzign.app.core.DisplayStatus
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.FullScreenError
import de.doorzign.app.ui.common.FullScreenLoading
import de.doorzign.app.ui.common.Loadable
import de.doorzign.app.ui.theme.StatusColors
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

enum class DisplayFilter(val label: Int) {
    ALL(R.string.filter_all), OFFLINE(R.string.filter_offline), BATTERY(R.string.filter_battery),
    ERROR(R.string.filter_error), TRANSFER(R.string.filter_transfer);

    fun matches(d: Display) = when (this) {
        ALL -> true
        OFFLINE -> !d.online
        BATTERY -> DisplayStatus.battery(d.battery).let { it == BatteryState.LOW || it == BatteryState.CRITICAL }
        ERROR -> !d.lastError.isNullOrBlank()
        TRANSFER -> d.openJobs > 0
    }
}

class DisplayListViewModel(private val conn: Connection) : ViewModel() {
    private val all = MutableStateFlow<Loadable<List<Display>>>(Loadable.Loading)
    val refreshing = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(DisplayFilter.ALL)
    val transientError = MutableStateFlow<String?>(null)
    private val collator = Collator.getInstance(Locale.GERMAN)

    val visible: StateFlow<Loadable<List<Display>>> = combine(all, query, filter) { l, q, f ->
        when (l) {
            is Loadable.Ready -> Loadable.Ready(l.value.filter { d ->
                f.matches(d) && (q.isBlank() || d.name.contains(q, true) || d.location.orEmpty().contains(q, true) || d.mac.contains(q, true))
            })
            else -> l
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Loadable.Loading)

    fun refresh(userInitiated: Boolean = false) {
        if (refreshing.value) return
        if (userInitiated) refreshing.value = true
        viewModelScope.launch {
            try {
                val list = apiCall { conn.api.displays.listDisplays() }.`data`.sortedWith(compareBy(collator) { it.name })
                all.value = Loadable.Ready(list)
                transientError.value = null
            } catch (e: ApiException) {
                // Bereits geladene Liste behalten, Fehler nur als Hinweis
                if (all.value is Loadable.Ready) transientError.value = Texts.error(e) else all.value = Loadable.Failed(Texts.error(e))
            } finally {
                refreshing.value = false
            }
        }
    }
}

/** Startseite: alle sichtbaren Displays mit Suche und Filter; aktualisiert sich alle 30 s, solange sichtbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayListScreen(conn: Connection, onOpen: (String) -> Unit) {
    val vm: DisplayListViewModel = viewModel { DisplayListViewModel(conn) }
    val data by vm.visible.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val transient by vm.transientError.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { vm.refresh(); delay(30_000) }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_displays)) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = { Text(stringResource(R.string.displays_search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DisplayFilter.entries) { f ->
                    FilterChip(selected = f == filter, onClick = { vm.filter.value = f }, label = { Text(stringResource(f.label)) })
                }
            }
            transient?.let { ErrorCard(it, Modifier.padding(horizontal = 16.dp)) }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh(userInitiated = true) }, modifier = Modifier.fillMaxSize()) {
                when (val d = data) {
                    Loadable.Loading -> FullScreenLoading()
                    is Loadable.Failed -> FullScreenError(d.message) { vm.refresh(true) }
                    is Loadable.Ready -> if (d.value.isEmpty()) {
                        Text(stringResource(R.string.displays_empty), Modifier.padding(24.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge)
                    } else LazyColumn(Modifier.fillMaxSize()) {
                        items(d.value, key = { it.id }) { display ->
                            DisplayRow(conn, display) { onOpen(display.id) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayRow(conn: Connection, d: Display, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        SlotPreview(conn, d.id, d.activeSlot, d.activePreviewEtag(), Modifier.width(96.dp).aspectRatio(4f / 3f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            d.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OnlineChip(d.online)
                BatteryLabel(d.battery)
                SignalLabel(d.signal)
            }
            when {
                !d.lastError.isNullOrBlank() -> Text(d.lastError!!, color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                d.openJobs > 0 -> Text(stringResource(R.string.displays_transferring), color = StatusColors.Warn,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
