package de.doorzign.app.ui.displays

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.doorzign.app.R
import de.doorzign.app.api.model.Slot
import de.doorzign.app.core.Level
import de.doorzign.app.core.SlotContent
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.FullScreenError
import de.doorzign.app.ui.common.FullScreenLoading
import de.doorzign.app.ui.common.Loadable
import de.doorzign.app.ui.common.appContainer
import de.doorzign.app.ui.theme.StatusColors
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DisplayDetailScreen(
    conn: Connection,
    displayId: String,
    onBack: () -> Unit,
    onEditSlot: (Int) -> Unit,
    onAdhoc: () -> Unit,
    onSchedules: () -> Unit,
    onJobs: () -> Unit,
) {
    val sessions = appContainer().sessions
    val vm: DisplayDetailViewModel = viewModel { DisplayDetailViewModel(conn, displayId) { sessions.features } }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { vm.refresh(); delay(vm.pollIntervalMs()) }
        }
    }
    val features = sessions.features
    var menu by remember { mutableStateOf(false) }
    val detail = (state as? Loadable.Ready)?.value

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(detail?.display?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (detail?.level == Level.EDIT) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.detail_resend)) }, onClick = { menu = false; vm.resend() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        when (val s = state) {
            Loadable.Loading -> FullScreenLoading()
            is Loadable.Failed -> FullScreenError(s.message, vm::refresh)
            is Loadable.Ready -> {
                val d = s.value
                val display = d.display
                val pager = rememberPagerState(initialPage = (display.activeSlot - 1).coerceIn(0, 2)) { d.slots.size }
                val scope = rememberCoroutineScope()
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    // Kopf
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOfNotNull(display.location, display.model).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }
                            ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OnlineChip(display.online)
                            if (!display.online) Text(stringResource(R.string.detail_last_seen, relativeTime(display.lastSeen)),
                                style = MaterialTheme.typography.labelMedium)
                            BatteryLabel(display.battery)
                            SignalLabel(display.signal)
                        }
                        LicenseLine(display.license.type, display.license.basic, display.license.validUntil)
                        display.lastError?.takeIf { it.isNotBlank() }?.let { Text(it, color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall) }
                    }
                    Spacer(Modifier.height(12.dp))

                    // Ansichten
                    HorizontalPager(pager, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp),
                        pageSpacing = 12.dp) { page ->
                        val slot = d.slots[page]
                        val active = slot.slot == display.activeSlot
                        // Menü nur mit erlaubten Aktionen; ohne Aktion bleibt die Karte ein reines Bild.
                        val canShow = d.level.atLeast(Level.SWITCH) && !active && slot.content !is SlotContent.Deactivated
                        val canEdit = d.level == Level.EDIT && slot.editable
                        var slotMenu by remember { mutableStateOf(false) }
                        Box {
                            SlotCard(conn, displayId, slot, active = active,
                                previewEtag = display.slots.firstOrNull { it.slot == slot.slot }?.previewEtag ?: slot.previewEtag,
                                onClick = if (canShow || canEdit) ({ slotMenu = true }) else null)
                            DropdownMenu(slotMenu, { slotMenu = false }) {
                                if (canShow) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.slot_show_n, slot.slot)) },
                                    leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                    enabled = !busy,
                                    onClick = { slotMenu = false; vm.showSlot(slot.slot) },
                                )
                                if (canShow && canEdit) HorizontalDivider()
                                if (canEdit) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_title, slot.slot)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { slotMenu = false; onEditSlot(slot.slot) },
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                        d.slots.forEachIndexed { i, sl ->
                            TextButton(onClick = { scope.launch { pager.animateScrollToPage(i) } }) {
                                Text(stringResource(R.string.slot_n, sl.slot),
                                    color = if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val current = d.slots.getOrNull(pager.currentPage)

                    // Aktionen
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.level.atLeast(Level.SWITCH) && features.identify) {
                            OutlinedButton(onClick = vm::identify, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FlashOn, null); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.detail_identify))
                            }
                        }
                        if (current != null && !current.editable) {
                            Text(stringResource(R.string.detail_not_editable), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()

                    // Abschnitte
                    if (features.adhoc) {
                        val a = d.adhoc
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.adhoc_title)) },
                            supportingContent = {
                                Text(if (a == null) stringResource(R.string.adhoc_none)
                                     else stringResource(R.string.adhoc_running, remaining(a.endsAt), a.content.message.ifBlank { a.content.headline }))
                            },
                            trailingContent = {
                                if (a != null && d.level == Level.EDIT) TextButton(onClick = vm::endAdhoc, enabled = !busy) { Text(stringResource(R.string.adhoc_end)) }
                                else if (d.level == Level.EDIT) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            },
                            modifier = Modifier.clickable(enabled = d.level == Level.EDIT, onClick = onAdhoc),
                        )
                        HorizontalDivider()
                    }
                    if (features.schedules) {
                        ListItem(headlineContent = { Text(stringResource(R.string.schedules_title)) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable(onClick = onSchedules))
                        HorizontalDivider()
                    }
                    d.calendar?.takeIf { it.configured }?.let { cal ->
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.calendar_title)) },
                            supportingContent = {
                                Column {
                                    cal.error?.let { Text(it, color = StatusColors.Bad) }
                                    cal.occupied?.let { Text(stringResource(if (it) R.string.calendar_occupied else R.string.calendar_free)) }
                                    cal.eventsToday.orEmpty().take(5).forEach { e ->
                                        Text("${localTime(e.start)}–${localTime(e.end)}  ${e.title}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (cal.eventsToday.isNullOrEmpty()) Text(stringResource(R.string.calendar_no_events), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.nav_jobs)) },
                        supportingContent = { if (display.openJobs > 0) Text(stringResource(R.string.jobs_open_count, display.openJobs), color = StatusColors.Warn) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        modifier = Modifier.clickable(onClick = onJobs),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotCard(conn: Connection, displayId: String, slot: Slot, active: Boolean, previewEtag: String?, onClick: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).then(
            if (active) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(12.dp)) else Modifier
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.slot_n, slot.slot) + " · " + slotTypeLabel(slot.content),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (active) StatusChip(stringResource(R.string.slot_active), MaterialTheme.colorScheme.primary)
            }
            SlotPreview(conn, displayId, slot.slot, previewEtag, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            when {
                slot.transfer != null -> StatusChip(jobStatusLabel(slot.transfer!!.status.value), StatusColors.Warn)
                slot.transferred -> StatusChip(stringResource(R.string.slot_transferred), StatusColors.Ok)
                // Nicht aktive Ansichten überträgt Zeitwart erst beim Umschalten – dort kein Hinweis.
                active && slot.content !is SlotContent.Deactivated -> StatusChip(stringResource(R.string.slot_not_transferred), StatusColors.Neutral)
            }
        }
    }
}

@Composable
private fun LicenseLine(type: String, basic: Boolean, validUntil: String?) {
    val label = when (type) {
        "doorzign" -> stringResource(R.string.license_doorzign)
        "ical_connect" -> stringResource(R.string.license_ical)
        "none" -> stringResource(R.string.license_none)
        else -> type
    }
    val text = if (validUntil != null) stringResource(R.string.license_until, label, validUntil) else label
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (basic) MaterialTheme.colorScheme.onSurfaceVariant else StatusColors.Bad)
}

@Composable
fun slotTypeLabel(c: SlotContent): String = stringResource(when (c) {
    is SlotContent.Text -> R.string.type_text
    is SlotContent.Image -> R.string.type_image
    is SlotContent.Calendar -> R.string.type_calendar
    SlotContent.Deactivated -> R.string.type_deactivated
})

/** Statusbezeichnungen laut ui-spec §7; unbekannte Werte neutral. */
@Composable
fun jobStatusLabel(status: String): String = when (status) {
    "pending" -> stringResource(R.string.job_pending)
    "progress" -> stringResource(R.string.job_progress)
    "retry" -> stringResource(R.string.job_retry)
    "done" -> stringResource(R.string.job_done)
    "failed" -> stringResource(R.string.job_failed)
    "skipped" -> stringResource(R.string.job_skipped)
    "abandoned" -> stringResource(R.string.job_abandoned)
    else -> status
}

@Composable
private fun remaining(endsAt: String): String {
    val end = runCatching { Instant.parse(endsAt) }.getOrNull() ?: return "–"
    val m = Duration.between(Instant.now(), end).toMinutes().coerceAtLeast(0)
    return de.doorzign.app.ui.common.LocaleFormat.duration(m)
}

/** ISO-Zeitpunkt → Uhrzeit in Gerätezeitzone und Geräteformat (24 h bzw. 12 h). */
fun localTime(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault(iso)
