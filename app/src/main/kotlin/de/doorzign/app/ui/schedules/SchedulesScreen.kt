package de.doorzign.app.ui.schedules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.doorzign.app.R
import de.doorzign.app.api.model.Display
import de.doorzign.app.api.model.Schedule
import de.doorzign.app.api.model.ScheduleInput
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.Level
import de.doorzign.app.core.ScheduleRules
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.BackTopBar
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.Texts
import de.doorzign.app.ui.common.FullScreenError
import de.doorzign.app.ui.common.FullScreenLoading
import de.doorzign.app.ui.common.Loadable
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SchedulesData(val display: Display, val schedules: List<Schedule>)

/** Bearbeitung: `existing == null` = neuer Zeitplan. */
data class ScheduleEdit(val existing: Schedule?, val draft: ScheduleRules.Draft, val errors: Map<String, String> = emptyMap())

class SchedulesViewModel(private val conn: Connection, private val displayId: String) : ViewModel() {
    val state = MutableStateFlow<Loadable<SchedulesData>>(Loadable.Loading)
    val edit = MutableStateFlow<ScheduleEdit?>(null)
    val deleting = MutableStateFlow<Schedule?>(null)
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init { load() }

    fun load() = viewModelScope.launch {
        state.value = try {
            val d = async { apiCall { conn.api.displays.getDisplay(displayId) } }
            val s = async { apiCall { conn.api.schedules.listSchedules(displayId) }.`data` }
            Loadable.Ready(SchedulesData(d.await(), s.await().sortedWith(compareBy({ it.start }, { it.name }))))
        } catch (e: ApiException) { Loadable.Failed(Texts.error(e)) }
    }

    fun create() { edit.value = ScheduleEdit(null, ScheduleRules.Draft()) }

    fun open(s: Schedule) {
        edit.value = ScheduleEdit(s, ScheduleRules.Draft(s.name, s.slot, s.weekdays.toSet(), s.start, s.end,
            s.validFrom.orEmpty(), s.validUntil.orEmpty(), s.priority.toString(), s.enabled))
    }

    fun change(f: (ScheduleRules.Draft) -> ScheduleRules.Draft) = edit.update { it?.copy(draft = f(it.draft), errors = emptyMap()) }
    fun cancel() { edit.value = null }

    fun save() {
        val e = edit.value ?: return
        val errs = ScheduleRules.validate(e.draft)
        if (errs.isNotEmpty()) { edit.value = e.copy(errors = errs.mapValues { (_, v) -> Texts.schedule(v) }); return }
        submit(e.existing, toInput(e.draft)) { edit.value = null }
    }

    fun toggle(s: Schedule, enabled: Boolean) =
        submit(s, ScheduleInput(s.slot, s.weekdays.toSet(), s.start, s.end, s.priority, enabled, s.name, s.validFrom, s.validUntil)) {}

    fun confirmDelete(s: Schedule?) { deleting.value = s }

    fun delete() {
        val s = deleting.value ?: return
        deleting.value = null
        act { apiCall { conn.api.schedules.deleteSchedule(s.id) }; load() }
    }

    private fun submit(existing: Schedule?, input: ScheduleInput, onOk: () -> Unit) = act {
        try {
            if (existing == null) apiCall { conn.api.schedules.createSchedule(displayId, input) }
            else apiCall { conn.api.schedules.updateSchedule(existing.id, input, ifMatch = existing.etag) }
            onOk()
        } catch (e: ApiException) {
            if (e.status == 412) { messages.tryEmit(Texts.get(R.string.schedule_conflict)); edit.value = null }
            else if (e.fields.isNotEmpty()) edit.update { it?.copy(errors = e.fields.mapValues { (_, v) -> v.joinToString() }) }
            else throw e
        }
        load()
    }

    private fun act(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() } catch (e: ApiException) { messages.tryEmit(Texts.error(e)) } finally { busy.value = false }
        }
    }

    private fun toInput(d: ScheduleRules.Draft) = ScheduleInput(
        slot = d.slot, weekdays = d.weekdays, start = d.start, end = d.end, priority = d.priority.toInt(), enabled = d.enabled,
        name = d.name.trim(), validFrom = d.validFrom.ifBlank { null }, validUntil = d.validUntil.ifBlank { null },
    )
}

private val WEEKDAYS = listOf(1 to R.string.wd_mo, 2 to R.string.wd_tu, 3 to R.string.wd_we, 4 to R.string.wd_th,
    5 to R.string.wd_fr, 6 to R.string.wd_sa, 7 to R.string.wd_su)

/** Zeitpläne (ui-spec §6). */
@Composable
fun SchedulesScreen(conn: Connection, displayId: String, onBack: () -> Unit) {
    val vm: SchedulesViewModel = viewModel { SchedulesViewModel(conn, displayId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val edit by vm.edit.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val data = (state as? Loadable.Ready)?.value
    val canEdit = data?.let { Level.of(it.display.level.value) == Level.EDIT } == true

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.schedules_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (canEdit) FloatingActionButton(onClick = vm::create) { Icon(Icons.Default.Add, stringResource(R.string.schedule_new)) } },
    ) { pad ->
        when (val s = state) {
            Loadable.Loading -> FullScreenLoading()
            is Loadable.Failed -> FullScreenError(s.message) { vm.load() }
            is Loadable.Ready -> LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                val tz = s.value.display.timezone
                if (tz != ZoneId.systemDefault().id) item {
                    Text(stringResource(R.string.schedule_tz_hint, tz), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (s.value.schedules.isEmpty()) item { Text(stringResource(R.string.schedule_empty), Modifier.padding(24.dp)) }
                items(s.value.schedules, key = { it.id }) { sch ->
                    ListItem(
                        headlineContent = { Text(sch.name.ifBlank { stringResource(R.string.slot_n, sch.slot) }) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.slot_n, sch.slot) + " · " + weekdaysLabel(sch.weekdays) + " · ${sch.start}–${sch.end}")
                                if (sch.validFrom != null || sch.validUntil != null) {
                                    Text(stringResource(R.string.schedule_valid, sch.validFrom ?: "…", sch.validUntil ?: "…"),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        trailingContent = { Switch(sch.enabled, { vm.toggle(sch, it) }, enabled = canEdit && !busy) },
                        modifier = Modifier.clickable(enabled = canEdit) { vm.open(sch) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    edit?.let { e -> ScheduleDialog(e, busy, vm) }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.confirmDelete(null) },
            title = { Text(stringResource(R.string.schedule_delete_title)) },
            text = { Text(d.name.ifBlank { "${d.start}–${d.end}" }) },
            confirmButton = { TextButton(onClick = vm::delete) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { vm.confirmDelete(null) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun weekdaysLabel(days: List<Int>): String {
    val names = WEEKDAYS.associate { (n, r) -> n to stringResource(r) }
    return days.sorted().joinToString(", ") { names[it] ?: "?" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDialog(e: ScheduleEdit, busy: Boolean, vm: SchedulesViewModel) {
    val d = e.draft
    AlertDialog(
        onDismissRequest = vm::cancel,
        title = { Text(stringResource(if (e.existing == null) R.string.schedule_new else R.string.schedule_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.name, { v -> vm.change { it.copy(name = v) } }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.schedule_name)) }, singleLine = true)
                Text(stringResource(R.string.schedule_slot), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { n -> FilterChip(d.slot == n, { vm.change { it.copy(slot = n) } }, { Text(stringResource(R.string.slot_n, n)) }) }
                }
                Text(stringResource(R.string.schedule_weekdays), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAYS.forEach { (n, r) ->
                        FilterChip(n in d.weekdays, { vm.change { it.copy(weekdays = if (n in it.weekdays) it.weekdays - n else it.weekdays + n) } },
                            { Text(stringResource(r)) })
                    }
                }
                e.errors["weekdays"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.schedule_start), d.start, e.errors["start"], Modifier.weight(1f), KeyboardType.Number) { v -> vm.change { it.copy(start = v) } }
                    Field(stringResource(R.string.schedule_end), d.end, e.errors["end"], Modifier.weight(1f), KeyboardType.Number) { v -> vm.change { it.copy(end = v) } }
                }
                if (ScheduleRules.overnight(d)) Text(stringResource(R.string.schedule_overnight), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.schedule_from), d.validFrom, e.errors["validFrom"], Modifier.weight(1f), KeyboardType.Number) { v -> vm.change { it.copy(validFrom = v) } }
                    Field(stringResource(R.string.schedule_until), d.validUntil, e.errors["validUntil"], Modifier.weight(1f), KeyboardType.Number) { v -> vm.change { it.copy(validUntil = v) } }
                }
                Field(stringResource(R.string.schedule_priority), d.priority, e.errors["priority"], Modifier.fillMaxWidth(), KeyboardType.Number) { v -> vm.change { it.copy(priority = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.schedule_enabled), Modifier.weight(1f))
                    Switch(d.enabled, { v -> vm.change { it.copy(enabled = v) } })
                }
                if (e.existing != null) TextButton(onClick = { vm.cancel(); vm.confirmDelete(e.existing) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::save, enabled = !busy) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Field(label: String, value: String, error: String?, modifier: Modifier, type: KeyboardType, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = type))
