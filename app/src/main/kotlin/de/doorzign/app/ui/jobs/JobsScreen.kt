package de.doorzign.app.ui.jobs

import de.doorzign.app.ui.common.Texts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.doorzign.app.R
import de.doorzign.app.api.model.Job
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.JobStates
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.BackTopBar
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.FullScreenError
import de.doorzign.app.ui.common.FullScreenLoading
import de.doorzign.app.ui.common.Loadable
import de.doorzign.app.ui.displays.StatusChip
import de.doorzign.app.ui.displays.jobStatusLabel
import de.doorzign.app.ui.displays.relativeTime
import de.doorzign.app.ui.displays.localTime
import de.doorzign.app.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class JobList(val jobs: List<Job>, val incomplete: Boolean)

class JobsViewModel(private val conn: Connection, private val displayId: String?) : ViewModel() {
    val state = MutableStateFlow<Loadable<JobList>>(Loadable.Loading)
    val refreshing = MutableStateFlow(false)
    val transient = MutableStateFlow<String?>(null)
    private var openSince: Long? = null

    fun refresh(user: Boolean = false) {
        if (user) refreshing.value = true
        viewModelScope.launch {
            try {
                val list = if (displayId != null) JobList(apiCall { conn.api.jobs.listDisplayJobs(displayId) }.`data`, false)
                    else apiCall { conn.api.jobs.listJobs() }.let { JobList(it.`data`, it.incomplete == true) }
                state.value = Loadable.Ready(list)
                transient.value = null
                val open = list.jobs.any { JobStates.isOpen(it.status.value) }
                openSince = if (open) openSince ?: System.currentTimeMillis() else null
            } catch (e: ApiException) {
                if (state.value is Loadable.Ready) transient.value = Texts.error(e) else state.value = Loadable.Failed(Texts.error(e))
            } finally {
                refreshing.value = false
            }
        }
    }

    /** ui-spec §7: Während offene Aufträge existieren alle 5 s (max. 5 min), danach 30 s. */
    fun interval(): Long {
        val s = openSince ?: return 30_000
        return if (System.currentTimeMillis() - s < 5 * 60_000) 5_000 else 30_000
    }
}

/** Übertragungen – pro Display ([displayId] gesetzt) oder global. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(conn: Connection, displayId: String?, onBack: (() -> Unit)?) {
    val vm: JobsViewModel = viewModel { JobsViewModel(conn, displayId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val transient by vm.transient.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { vm.refresh(); delay(vm.interval()) } }
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.nav_jobs), onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            transient?.let { ErrorCard(it, Modifier.padding(16.dp)) }
            PullToRefreshBox(refreshing, { vm.refresh(true) }, Modifier.fillMaxSize()) {
                when (val s = state) {
                    Loadable.Loading -> FullScreenLoading()
                    is Loadable.Failed -> FullScreenError(s.message) { vm.refresh(true) }
                    is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize()) {
                        if (s.value.incomplete) item {
                            Text(stringResource(R.string.jobs_incomplete), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.value.jobs.isEmpty()) item { Text(stringResource(R.string.jobs_empty), Modifier.padding(24.dp)) }
                        items(s.value.jobs, key = { it.id }) { job ->
                            JobRow(job, showDisplay = displayId == null)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: Job, showDisplay: Boolean) {
    val status = job.status.value
    val color = when (status) {
        "done" -> StatusColors.Ok
        "failed", "abandoned" -> StatusColors.Bad
        "skipped" -> StatusColors.Neutral
        else -> StatusColors.Warn
    }
    ListItem(
        overlineContent = if (showDisplay) ({ Text(job.displayName ?: job.displayId) }) else null,
        headlineContent = { Text(jobTypeLabel(job.type) + (job.slot?.let { " · " + stringResource(R.string.slot_n, it) } ?: "")) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(relativeTime(job.updatedAt ?: job.createdAt), style = MaterialTheme.typography.bodySmall)
                job.errorMessage?.takeIf { it.isNotBlank() }?.let { Text(it, color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall) }
                if (status == "retry") job.nextTryAt?.let {
                    Text(stringResource(R.string.jobs_next_try, localTime(it)), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = { StatusChip(jobStatusLabel(status), color) },
    )
}

/** Offene Werteliste `Job.type` – Unbekanntes neutral. */
@Composable
fun jobTypeLabel(type: String): String = when (type) {
    "upload_image" -> stringResource(R.string.jobtype_upload)
    "press_button" -> stringResource(R.string.jobtype_press)
    "unbind_button" -> stringResource(R.string.jobtype_unbind)
    "identify" -> stringResource(R.string.jobtype_identify)
    else -> stringResource(R.string.jobtype_other)
}
