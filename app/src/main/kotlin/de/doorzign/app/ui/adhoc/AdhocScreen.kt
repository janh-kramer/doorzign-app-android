package de.doorzign.app.ui.adhoc

import de.doorzign.app.ui.common.Texts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.doorzign.app.R
import de.doorzign.app.api.infrastructure.Success
import de.doorzign.app.api.model.Adhoc
import de.doorzign.app.api.model.StartAdhocRequest
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.SlotContent
import de.doorzign.app.core.TextContent
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.BackTopBar
import de.doorzign.app.ui.common.BusyButton
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.FullScreenError
import de.doorzign.app.ui.common.FullScreenLoading
import de.doorzign.app.ui.common.Loadable
import de.doorzign.app.ui.displays.localTime
import de.doorzign.app.ui.editor.ColorRow
import de.doorzign.app.ui.editor.SlotEditorViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AdhocViewModel(private val conn: Connection, private val displayId: String) : ViewModel() {
    val running = MutableStateFlow<Loadable<Adhoc?>>(Loadable.Loading)
    val text = MutableStateFlow(SlotEditorViewModel.DEFAULT_TEXT)
    val duration = MutableStateFlow(60)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val fields = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init { load() }

    fun load() {
        viewModelScope.launch {
            running.value = try {
                val r = apiCall { conn.api.contents.getAdhocWithHttpInfo(displayId) }
                Loadable.Ready((r as? Success<*>)?.data as? Adhoc)
            } catch (e: ApiException) { Loadable.Failed(Texts.error(e)) }
        }
    }

    fun update(f: (SlotContent.Text) -> SlotContent.Text) = text.update(f)

    fun start() = act {
        apiCall { conn.api.contents.startAdhoc(displayId, StartAdhocRequest(TextContent.of(text.value), duration.value)) }
        done.tryEmit(Unit)
    }

    fun end() = act {
        apiCall { conn.api.contents.endAdhoc(displayId) }
        load()
    }

    private fun act(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null; fields.value = emptyMap()
        viewModelScope.launch {
            try { block() } catch (e: ApiException) { error.value = Texts.error(e); fields.value = e.fields } finally { busy.value = false }
        }
    }

    companion object {
        val DURATIONS = listOf(15, 30, 45, 60, 90, 120, 180, 240)
    }
}

/** Ad-hoc-Nachricht (ui-spec §5). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdhocScreen(conn: Connection, displayId: String, onDone: () -> Unit) {
    val vm: AdhocViewModel = viewModel { AdhocViewModel(conn, displayId) }
    val running by vm.running.collectAsStateWithLifecycle()
    val t by vm.text.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fields by vm.fields.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { onDone() } }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.adhoc_title), onDone) }) { pad ->
        when (val r = running) {
            Loadable.Loading -> FullScreenLoading()
            is Loadable.Failed -> FullScreenError(r.message, vm::load)
            is Loadable.Ready -> Column(
                Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { ErrorCard(it) }
                r.value?.let { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.adhoc_current_until, localTime(a.endsAt)), style = MaterialTheme.typography.titleMedium)
                            listOf(a.content.title, a.content.headline, a.content.message).filter { it.isNotBlank() }.forEach { Text(it) }
                            OutlinedButton(onClick = vm::end, enabled = !busy) { Text(stringResource(R.string.adhoc_end)) }
                        }
                    }
                    Text(stringResource(R.string.adhoc_replace_hint), style = MaterialTheme.typography.bodySmall)
                }
                Field(stringResource(R.string.text_title), t.title, 50, fields["content.title"]) { v -> vm.update { it.copy(title = v) } }
                Field(stringResource(R.string.text_headline), t.headline, 75, fields["content.headline"]) { v -> vm.update { it.copy(headline = v) } }
                Field(stringResource(R.string.text_subheadline), t.subHeadline, 50, fields["content.subHeadline"]) { v -> vm.update { it.copy(subHeadline = v) } }
                Field(stringResource(R.string.text_message), t.message, 100, fields["content.message"]) { v -> vm.update { it.copy(message = v) } }
                ColorRow(stringResource(R.string.color_text), t.color) { c -> vm.update { it.copy(color = c) } }
                ColorRow(stringResource(R.string.color_bg), t.bg) { c -> vm.update { it.copy(bg = c) } }
                ColorRow(stringResource(R.string.color_message), t.messageColor) { c -> vm.update { it.copy(messageColor = c) } }
                ColorRow(stringResource(R.string.color_message_bg), t.messageBg) { c -> vm.update { it.copy(messageBg = c) } }
                Text(stringResource(R.string.adhoc_duration), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdhocViewModel.DURATIONS.forEach { m ->
                        FilterChip(selected = m == duration, onClick = { vm.duration.value = m },
                            label = { Text(if (m < 60) stringResource(R.string.duration_m, m) else stringResource(R.string.duration_hours, m / 60.0)) })
                    }
                }
                fields["durationMinutes"]?.let { Text(it.joinToString(), color = MaterialTheme.colorScheme.error) }
                BusyButton(stringResource(R.string.adhoc_show_now), busy, vm::start, Modifier.fillMaxWidth(),
                    enabled = listOf(t.title, t.headline, t.subHeadline, t.message).any { it.isNotBlank() })
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, max: Int, error: List<String>?, onChange: (String) -> Unit) =
    OutlinedTextField(value, { onChange(it.take(max)) }, Modifier.fillMaxWidth(), label = { Text(label) },
        isError = error != null, supportingText = { Text(error?.joinToString() ?: "${value.length} / $max") })
