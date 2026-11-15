package de.doorzign.app.ui.displays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.doorzign.app.R
import de.doorzign.app.ui.common.Texts
import de.doorzign.app.api.model.Adhoc
import de.doorzign.app.api.model.CalendarStatus
import de.doorzign.app.api.model.Display
import de.doorzign.app.api.model.SetActiveSlotRequest
import de.doorzign.app.api.model.Slot
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.Level
import de.doorzign.app.core.SlotContent
import de.doorzign.app.core.SystemFeatures
import de.doorzign.app.core.apiCall
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.Loadable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class DisplayDetail(
    val display: Display,
    val slots: List<Slot>,
    val adhoc: Adhoc?,
    val calendar: CalendarStatus?,
) {
    val level: Level get() = Level.of(display.level.value)
}

class DisplayDetailViewModel(
    private val conn: Connection,
    val displayId: String,
    private val features: () -> SystemFeatures,
) : ViewModel() {
    val state = MutableStateFlow<Loadable<DisplayDetail>>(Loadable.Loading)
    val busy = MutableStateFlow(false)
    /** Einmalige Meldungen (Snackbar). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var openSince: Long? = null

    fun refresh() {
        viewModelScope.launch {
            try {
                val f = features()
                val d = async { apiCall { conn.api.displays.getDisplay(displayId) } }
                val s = async { apiCall { conn.api.contents.listSlots(displayId) }.`data`.sortedBy { it.slot } }
                val a = async {
                    if (!f.adhoc) null
                    else apiCall { conn.api.contents.getAdhocWithHttpInfo(displayId) }.let { r ->
                        (r as? de.doorzign.app.api.infrastructure.Success<*>)?.data as? Adhoc
                    }
                }
                val slots = s.await()
                val calSlot = slots.firstOrNull { it.content is SlotContent.Calendar }?.slot
                val cal = if (calSlot == null) null
                    else runCatching { apiCall { conn.api.calendars.getCalendarStatus(displayId, calSlot) } }.getOrNull()
                val detail = DisplayDetail(d.await(), slots, a.await(), cal)
                state.value = Loadable.Ready(detail)
                trackOpenJobs(detail)
            } catch (e: ApiException) {
                if (state.value is Loadable.Ready) messages.tryEmit(Texts.error(e)) else state.value = Loadable.Failed(Texts.error(e))
            }
        }
    }

    /** Polling-Intervall laut ui-spec §7: offene Aufträge → 5 s (max. 5 min), danach/sonst 30 s. */
    fun pollIntervalMs(): Long {
        val since = openSince ?: return 30_000
        return if (System.currentTimeMillis() - since < 5 * 60_000) 5_000 else 30_000
    }

    private fun trackOpenJobs(d: DisplayDetail) {
        val open = d.display.openJobs > 0 || d.slots.any { it.transfer != null }
        openSince = if (open) openSince ?: System.currentTimeMillis() else null
    }

    fun showSlot(slot: Int) = action(Texts.get(R.string.msg_slot_shown, slot)) {
        apiCall { conn.api.displays.setActiveSlot(displayId, SetActiveSlotRequest(slot)) }
    }

    fun identify() = action(Texts.get(R.string.msg_identify)) { apiCall { conn.api.displays.identifyDisplay(displayId) } }

    fun resend() = action(Texts.get(R.string.msg_resend)) { apiCall { conn.api.displays.resendDisplay(displayId) } }

    fun endAdhoc() = action(Texts.get(R.string.msg_adhoc_ended)) { apiCall { conn.api.contents.endAdhoc(displayId) } }

    private fun action(success: String, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
                messages.tryEmit(success)
                openSince = System.currentTimeMillis()
                refresh()
            } catch (e: ApiException) {
                messages.tryEmit(Texts.error(e))
            } finally {
                busy.value = false
            }
        }
    }
}
