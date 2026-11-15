package de.doorzign.app.ui.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.doorzign.app.R
import de.doorzign.app.ui.common.Texts
import de.doorzign.app.api.model.CheckCalendarUrlRequest
import de.doorzign.app.api.model.Display
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.SlotContent
import de.doorzign.app.core.SystemFeatures
import de.doorzign.app.core.TextLimits
import de.doorzign.app.core.apiCall
import de.doorzign.app.core.renderPreview
import de.doorzign.app.core.uploadMedia
import de.doorzign.app.data.Connection
import de.doorzign.app.data.ImagePrep
import de.doorzign.app.ui.common.Loadable
import java.net.URI
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SlotType { TEXT, IMAGE, CALENDAR, DEACTIVATED }

/** Bearbeitungsstand: je Typ ein eigener Entwurf, damit ein Typwechsel keine Eingaben verwirft. */
data class EditorDraft(
    val type: SlotType,
    val text: SlotContent.Text,
    val image: SlotContent.Image,
    val calendar: SlotContent.Calendar,
) {
    val content: SlotContent get() = when (type) {
        SlotType.TEXT -> text
        SlotType.IMAGE -> image
        SlotType.CALENDAR -> calendar
        SlotType.DEACTIVATED -> SlotContent.Deactivated
    }
}

data class EditorInfo(
    val display: Display,
    val original: SlotContent,
    val etag: String,
    val editable: Boolean,
)

sealed interface CalendarCheck {
    data object Idle : CalendarCheck
    data object Running : CalendarCheck
    data class Ok(val events: Int?) : CalendarCheck
    data class Failed(val message: String) : CalendarCheck
}

class SlotEditorViewModel(
    private val conn: Connection,
    val displayId: String,
    val slot: Int,
    private val features: () -> SystemFeatures,
    private val mediaMaxBytes: () -> Int,
) : ViewModel() {
    val info = MutableStateFlow<Loadable<EditorInfo>>(Loadable.Loading)
    val draft = MutableStateFlow<EditorDraft?>(null)
    val preview = MutableStateFlow<ByteArray?>(null)
    val previewError = MutableStateFlow<String?>(null)
    val previewBusy = MutableStateFlow(false)
    /** Ein Text ist zu lang: keine Anfrage, letzte gültige Vorschau bleibt gedimmt stehen (ui-spec §4). */
    val previewStale = MutableStateFlow(false)
    val saving = MutableStateFlow(false)
    val uploading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val fieldErrors = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val conflict = MutableStateFlow(false)
    val calendarCheck = MutableStateFlow<CalendarCheck>(CalendarCheck.Idle)
    val saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        load()
        startPreview()
    }

    fun load() {
        info.value = Loadable.Loading
        viewModelScope.launch {
            try {
                val display = apiCall { conn.api.displays.getDisplay(displayId) }
                val s = apiCall { conn.api.contents.getSlot(displayId, slot) }
                info.value = Loadable.Ready(EditorInfo(display, s.content, s.etag, s.editable))
                draft.value = initialDraft(s.content)
                conflict.value = false
            } catch (e: ApiException) {
                info.value = Loadable.Failed(Texts.error(e))
            }
        }
    }

    val dirty: Boolean get() {
        val i = (info.value as? Loadable.Ready)?.value ?: return false
        return draft.value?.content != i.original
    }

    // --- Erlaubte Typen (ui-spec §4) ---

    fun calendarAllowed(i: EditorInfo): Boolean =
        slot == 1 && i.display.license.calendar && features().calendarIcal

    fun deactivateAllowed(i: EditorInfo): Boolean = i.display.activeSlot != slot

    // --- Änderungen ---

    fun setType(t: SlotType) = draft.update { it?.copy(type = t) }
    fun updateText(f: (SlotContent.Text) -> SlotContent.Text) = draft.update { it?.copy(text = f(it.text)) }
    fun updateImage(f: (SlotContent.Image) -> SlotContent.Image) = draft.update { it?.copy(image = f(it.image)) }
    fun updateCalendar(f: (SlotContent.Calendar) -> SlotContent.Calendar) {
        draft.update { it?.copy(calendar = f(it.calendar)) }
        calendarCheck.value = CalendarCheck.Idle
    }

    fun clearError() { error.value = null }

    /** Bild aus Kamera oder Galerie: verkleinern, als JPEG hochladen, `mediaId` übernehmen. */
    fun pickImage(context: Context, uri: Uri) {
        uploading.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val bytes = ImagePrep.prepare(context, uri, mediaMaxBytes())
                val media = conn.session.uploadMedia(bytes, "image/jpeg", "foto.jpg")
                updateImage { it.copy(mediaId = media.id) }
            } catch (e: Exception) {
                error.value = if (e is ApiException || e is ImagePrep.TooLarge) Texts.error(e) else Texts.get(R.string.err_image_unreadable)
            } finally {
                uploading.value = false
            }
        }
    }

    // --- Medienbibliothek (Feature media.library) ---
    val library = MutableStateFlow<Loadable<List<de.doorzign.app.api.model.Media>>?>(null)

    val libraryAvailable: Boolean get() = features().mediaLibrary

    fun openLibrary() {
        library.value = Loadable.Loading
        viewModelScope.launch {
            library.value = try {
                Loadable.Ready(apiCall { conn.api.contents.listMedia() }.`data`)
            } catch (e: ApiException) {
                Loadable.Failed(Texts.error(e))
            }
        }
    }

    /** Auswahl setzt nur die `mediaId` – kein erneuter Upload. */
    fun chooseFromLibrary(mediaId: String) {
        updateImage { it.copy(mediaId = mediaId) }
        library.value = null
    }

    fun closeLibrary() { library.value = null }

    fun mediaUrl(id: String) = "${conn.profile.apiBase}/media/${android.net.Uri.encode(id)}"
    val imageLoader get() = conn.imageLoader

    fun checkCalendar() {
        val url = normalizeIcalUrl(draft.value?.calendar?.url.orEmpty()) ?: run {
            calendarCheck.value = CalendarCheck.Failed(Texts.get(R.string.calendar_invalid_url))
            return
        }
        updateCalendar { it.copy(url = url) }
        calendarCheck.value = CalendarCheck.Running
        viewModelScope.launch {
            calendarCheck.value = try {
                val r = apiCall { conn.api.calendars.checkCalendarUrl(displayId, CheckCalendarUrlRequest(URI(url))) }
                if (r.ok) CalendarCheck.Ok(r.eventCount) else CalendarCheck.Failed(r.error ?: Texts.get(R.string.calendar_check_failed))
            } catch (e: ApiException) {
                CalendarCheck.Failed(calendarError(e))
            }
        }
    }

    /** Speichern mit `If-Match`; bei 412 Konfliktdialog, [overwrite] speichert ohne Versionsprüfung. */
    fun save(overwrite: Boolean = false) {
        val i = (info.value as? Loadable.Ready)?.value ?: return
        var content = draft.value?.content ?: return
        if (content is SlotContent.Calendar) {
            content = content.copy(url = content.url?.let { normalizeIcalUrl(it) ?: it })
        }
        // Vertragsgrenzen vorab prüfen, statt sehenden Auges in 422 zu laufen (Edge-Web erlaubt längere Texte)
        if (content is SlotContent.Text) {
            val over = TextLimits.exceeded(content)
            if (over.isNotEmpty()) {
                error.value = Texts.get(R.string.editor_text_too_long)
                fieldErrors.value = emptyMap()
                return
            }
        }
        saving.value = true
        error.value = null
        fieldErrors.value = emptyMap()
        viewModelScope.launch {
            try {
                apiCall { conn.api.contents.updateSlot(displayId, slot, content, ifMatch = if (overwrite) null else i.etag) }
                conflict.value = false
                saved.tryEmit(Unit)
            } catch (e: ApiException) {
                when {
                    e.status == 412 || e.code == "precondition_failed" -> conflict.value = true
                    else -> {
                        val ical = content is SlotContent.Calendar && (content as SlotContent.Calendar).source == SlotContent.Calendar.Source.ICAL
                        error.value = if (ical) calendarError(e) else Texts.error(e)
                        fieldErrors.value = e.fields
                    }
                }
            } finally {
                saving.value = false
            }
        }
    }

    fun dismissConflict() { conflict.value = false }

    /** Live-Vorschau: 600 ms nach der letzten Änderung `POST …/preview` (ui-spec §4). */
    @OptIn(FlowPreview::class)
    private fun startPreview() {
        viewModelScope.launch {
            draft.filterNotNull().distinctUntilChanged().debounce(600).collectLatest { d ->
                val c = d.content
                if (c is SlotContent.Image && c.mediaId == null) { preview.value = null; return@collectLatest }
                if (c is SlotContent.Calendar && c.url.isNullOrBlank() && c.source == SlotContent.Calendar.Source.ICAL) {
                    preview.value = null; return@collectLatest
                }
                if (c is SlotContent.Text && TextLimits.exceeded(c).isNotEmpty()) {
                    // Server antwortet sonst mit 422 – erst nach dem Kürzen wieder anfragen
                    previewStale.value = true
                    previewError.value = null
                    return@collectLatest
                }
                previewStale.value = false
                previewBusy.value = true
                try {
                    preview.value = conn.session.renderPreview(displayId, slot, c)
                    previewError.value = null
                } catch (e: ApiException) {
                    // 422 mit fields: Hinweis zum betroffenen Feld statt allgemeinem Vorschaufehler
                    previewError.value = if (e.status == 422 && e.fields.isNotEmpty()) fieldHint(e.fields) else Texts.error(e)
                } finally {
                    previewBusy.value = false
                }
            }
        }
    }

    /** `unlicensed` im Kalenderkontext meint die Kalenderlizenz, sonst die Displaylizenz (Texts.error). */
    private fun calendarError(e: ApiException): String =
        if (e.code == "unlicensed") Texts.get(R.string.err_unlicensed_calendar) else Texts.error(e)

    private fun fieldHint(fields: Map<String, List<String>>): String =
        fields.entries.joinToString("\n") { (key, msgs) ->
            val name = key.removePrefix("content.")
            val label = FIELD_LABELS[name]?.let { Texts.get(it) } ?: name
            Texts.get(R.string.preview_field_error, label, msgs.joinToString())
        }

    companion object {
        /** Anzeigenamen der Inhaltsfelder für Feldhinweise aus 422-Antworten. */
        private val FIELD_LABELS = mapOf(
            "title" to R.string.text_title, "headline" to R.string.text_headline,
            "subHeadline" to R.string.text_subheadline, "message" to R.string.text_message,
            "color" to R.string.color_text, "bg" to R.string.color_bg,
            "messageColor" to R.string.color_message, "messageBg" to R.string.color_message_bg,
            "url" to R.string.calendar_url, "source" to R.string.calendar_source,
        )

        /** Standardfarben einer neuen Text-Ansicht (ui-spec §4). */
        val DEFAULT_TEXT = SlotContent.Text(
            color = SlotContent.Color.BLACK, bg = SlotContent.Color.WHITE,
            messageColor = SlotContent.Color.WHITE, messageBg = SlotContent.Color.BLACK,
        )

        fun initialDraft(c: SlotContent) = EditorDraft(
            type = when (c) {
                is SlotContent.Text -> SlotType.TEXT
                is SlotContent.Image -> SlotType.IMAGE
                is SlotContent.Calendar -> SlotType.CALENDAR
                SlotContent.Deactivated -> SlotType.DEACTIVATED
            },
            text = (c as? SlotContent.Text)?.withDefaults() ?: DEFAULT_TEXT,
            image = c as? SlotContent.Image ?: SlotContent.Image(mediaId = null, background = SlotContent.Color.WHITE),
            calendar = c as? SlotContent.Calendar ?: SlotContent.Calendar(
                source = SlotContent.Calendar.Source.ICAL, url = "", name = "", timezone = "Europe/Berlin"),
        )

        private fun SlotContent.Text.withDefaults() = copy(
            color = color ?: SlotContent.Color.BLACK, bg = bg ?: SlotContent.Color.WHITE,
            messageColor = messageColor ?: SlotContent.Color.WHITE, messageBg = messageBg ?: SlotContent.Color.BLACK,
        )

        /** `webcal://` → `https://`; nur http(s) zulassen. */
        fun normalizeIcalUrl(raw: String): String? {
            val t = raw.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
            val u = runCatching { URI(t) }.getOrNull() ?: return null
            return t.takeIf { (u.scheme == "https" || u.scheme == "http") && !u.host.isNullOrEmpty() }
        }
    }
}
