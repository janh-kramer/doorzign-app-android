package de.doorzign.app.ui.editor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.doorzign.app.R
import de.doorzign.app.core.TextLimits
import de.doorzign.app.core.SlotContent
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.common.BusyButton
import de.doorzign.app.ui.common.ErrorCard
import de.doorzign.app.ui.common.FullScreenError
import de.doorzign.app.ui.common.FullScreenLoading
import de.doorzign.app.ui.common.Loadable
import de.doorzign.app.ui.common.appContainer
import de.doorzign.app.ui.theme.StatusColors
import java.io.File

/** Ansicht bearbeiten (ui-spec §4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotEditorScreen(conn: Connection, displayId: String, slot: Int, onDone: () -> Unit) {
    val sessions = appContainer().sessions
    val vm: SlotEditorViewModel = viewModel {
        SlotEditorViewModel(conn, displayId, slot, { sessions.features }, { sessions.system.value?.mediaMaxBytes ?: 5_000_000 })
    }
    val info by vm.info.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val conflict by vm.conflict.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.saved.collect { onDone() } }
    val leave = { if (vm.dirty) confirmLeave = true else onDone() }
    BackHandler(onBack = leave)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.editor_title, slot)) },
            navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = {
                val i = (info as? Loadable.Ready)?.value
                if (i != null && i.editable && draft != null) {
                    TextButton(onClick = { vm.save() }, enabled = !saving) { Text(stringResource(R.string.action_save)) }
                }
            },
        )
    }) { pad ->
        when (val l = info) {
            Loadable.Loading -> FullScreenLoading()
            is Loadable.Failed -> FullScreenError(l.message, vm::load)
            is Loadable.Ready -> {
                val i = l.value
                val d = draft ?: return@Scaffold
                Column(
                    Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { ErrorCard(it) }
                    PreviewBox(vm)
                    if (!i.editable || (d.content as? SlotContent.Calendar)?.source == SlotContent.Calendar.Source.ZEITWART) {
                        Text(stringResource(R.string.detail_not_editable), style = MaterialTheme.typography.bodyMedium)
                        return@Column
                    }
                    TypeSelector(d.type, calendar = vm.calendarAllowed(i) || d.type == SlotType.CALENDAR,
                        deactivate = vm.deactivateAllowed(i), onSelect = vm::setType)
                    if (!vm.deactivateAllowed(i)) {
                        Text(stringResource(R.string.editor_active_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    when (d.type) {
                        SlotType.TEXT -> TextEditor(d.text, vm)
                        SlotType.IMAGE -> ImageEditor(d.image, vm)
                        SlotType.CALENDAR -> CalendarEditor(d.calendar, vm)
                        SlotType.DEACTIVATED -> Text(stringResource(R.string.editor_deactivated_hint))
                    }
                    BusyButton(stringResource(R.string.action_save), saving, { vm.save() }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = { Text(stringResource(R.string.editor_discard_text)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onDone() }) { Text(stringResource(R.string.editor_discard)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (conflict) AlertDialog(
        onDismissRequest = vm::dismissConflict,
        title = { Text(stringResource(R.string.editor_conflict_title)) },
        text = { Text(stringResource(R.string.editor_conflict_text)) },
        confirmButton = { TextButton(onClick = { vm.save(overwrite = true) }) { Text(stringResource(R.string.editor_overwrite)) } },
        dismissButton = { TextButton(onClick = { vm.dismissConflict(); vm.load() }) { Text(stringResource(R.string.editor_reload)) } },
    )
}

@Composable
private fun PreviewBox(vm: SlotEditorViewModel) {
    val png by vm.preview.collectAsStateWithLifecycle()
    val busy by vm.previewBusy.collectAsStateWithLifecycle()
    val err by vm.previewError.collectAsStateWithLifecycle()
    val stale by vm.previewStale.collectAsStateWithLifecycle()
    val bitmap = remember(png) { png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, stringResource(R.string.editor_preview), Modifier.fillMaxSize().alpha(if (stale) 0.35f else 1f),
            contentScale = ContentScale.Fit)
        when {
            stale -> Text(stringResource(R.string.editor_preview_after_shorten), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp)).padding(8.dp))
            bitmap == null && !busy -> Text(stringResource(R.string.editor_no_preview), style = MaterialTheme.typography.bodySmall)
        }
        if (busy) CircularProgressIndicator(Modifier.size(32.dp))
    }
    err?.let { Text(it, color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun TypeSelector(type: SlotType, calendar: Boolean, deactivate: Boolean, onSelect: (SlotType) -> Unit) {
    val options = listOf(
        Triple(SlotType.TEXT, R.string.type_text, true),
        Triple(SlotType.IMAGE, R.string.type_image, true),
        Triple(SlotType.CALENDAR, R.string.type_calendar, calendar),
        Triple(SlotType.DEACTIVATED, R.string.type_deactivated, deactivate || type == SlotType.DEACTIVATED),
    ).filter { it.first != SlotType.CALENDAR || calendar }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, (t, label, enabled) ->
            SegmentedButton(selected = t == type, onClick = { onSelect(t) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(idx, options.size)) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

/**
 * Feld mit Vertragsgrenze. Neue Eingaben werden auf [max] begrenzt; ein vom Backend geladener,
 * bereits zu langer Text bleibt erhalten, darf nur kürzer werden und wird als Fehler markiert.
 */
@Composable
private fun LimitedField(label: String, value: String, max: Int, error: List<String>?, onChange: (String) -> Unit) {
    val over = value.length > max
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            when {
                new.length <= max -> onChange(new)
                new.length <= value.length -> onChange(new) // zu lang geladen: kürzen/ändern erlaubt, wachsen nicht
                else -> onChange(new.take(maxOf(max, value.length)))
            }
        },
        label = { Text(label) },
        supportingText = {
            Text(error?.joinToString() ?: if (over) stringResource(R.string.field_too_long, value.length, max) else "${value.length} / $max")
        },
        isError = error != null || over,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextEditor(t: SlotContent.Text, vm: SlotEditorViewModel) {
    val fields by vm.fieldErrors.collectAsStateWithLifecycle()
    LimitedField(stringResource(R.string.text_title), t.title, TextLimits.TITLE, fields["title"]) { v -> vm.updateText { it.copy(title = v) } }
    LimitedField(stringResource(R.string.text_headline), t.headline, TextLimits.HEADLINE, fields["headline"]) { v -> vm.updateText { it.copy(headline = v) } }
    LimitedField(stringResource(R.string.text_subheadline), t.subHeadline, TextLimits.SUB_HEADLINE, fields["subHeadline"]) { v -> vm.updateText { it.copy(subHeadline = v) } }
    LimitedField(stringResource(R.string.text_message), t.message, TextLimits.MESSAGE, fields["message"]) { v -> vm.updateText { it.copy(message = v) } }
    ColorRow(stringResource(R.string.color_text), t.color) { c -> vm.updateText { it.copy(color = c) } }
    ColorRow(stringResource(R.string.color_bg), t.bg) { c -> vm.updateText { it.copy(bg = c) } }
    ColorRow(stringResource(R.string.color_message), t.messageColor) { c -> vm.updateText { it.copy(messageColor = c) } }
    ColorRow(stringResource(R.string.color_message_bg), t.messageBg) { c -> vm.updateText { it.copy(messageBg = c) } }
}

/** Farbwahl Schwarz/Weiß/Rot (E-Paper). */
@Composable
fun ColorRow(label: String, value: SlotContent.Color?, onChange: (SlotContent.Color) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        val colors = listOf(SlotContent.Color.BLACK to R.string.color_black, SlotContent.Color.WHITE to R.string.color_white,
            SlotContent.Color.RED to R.string.color_red)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            colors.forEachIndexed { i, (c, l) ->
                SegmentedButton(selected = value == c, onClick = { onChange(c) }, shape = SegmentedButtonDefaults.itemShape(i, colors.size)) {
                    Text(stringResource(l))
                }
            }
        }
    }
}

@Composable
private fun ImageEditor(img: SlotContent.Image, vm: SlotEditorViewModel) {
    val context = LocalContext.current
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.pickImage(context, it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { vm.pickImage(context, it) } }

    if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(if (img.mediaId == null) R.string.image_none else R.string.image_selected), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "aufnahme.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            cameraUri = uri
            camera.launch(uri)
        }, enabled = !uploading, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.image_camera))
        }
        OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !uploading, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.image_gallery))
        }
    }
    if (vm.libraryAvailable) {
        OutlinedButton(onClick = vm::openLibrary, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Collections, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.image_library))
        }
        LibraryDialog(vm)
    }
    Text(stringResource(R.string.image_fit), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(img.fit == SlotContent.Fit.FILL, { vm.updateImage { it.copy(fit = SlotContent.Fit.FILL) } },
            SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.image_fit_fill)) }
        SegmentedButton(img.fit == SlotContent.Fit.CROP, { vm.updateImage { it.copy(fit = SlotContent.Fit.CROP) } },
            SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.image_fit_crop)) }
    }
    ColorRow(stringResource(R.string.color_bg), img.background) { c -> vm.updateImage { it.copy(background = c) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.image_dither), Modifier.weight(1f))
        Switch(img.dither, { v -> vm.updateImage { it.copy(dither = v) } })
    }
}

/** Raster aus `GET /media`; Miniaturen über `GET /media/{id}` mit dem Bildlader der Sitzung (Bearer). */
@Composable
private fun LibraryDialog(vm: SlotEditorViewModel) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val l = lib ?: return
    AlertDialog(
        onDismissRequest = vm::closeLibrary,
        title = { Text(stringResource(R.string.library_title)) },
        text = {
            when (l) {
                Loadable.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Loadable.Failed -> ErrorCard(l.message, onRetry = vm::openLibrary)
                is Loadable.Ready -> if (l.value.isEmpty()) Text(stringResource(R.string.library_empty)) else
                    LazyVerticalGrid(GridCells.Adaptive(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(l.value, key = { it.id }) { m ->
                            AsyncImage(
                                model = vm.mediaUrl(m.id), imageLoader = vm.imageLoader,
                                contentDescription = m.fileName ?: m.id, contentScale = ContentScale.Crop,
                                modifier = Modifier.aspectRatio(1f).background(Color(0xFFEEEEEE)).clickable { vm.chooseFromLibrary(m.id) },
                            )
                        }
                    }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::closeLibrary) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CalendarEditor(cal: SlotContent.Calendar, vm: SlotEditorViewModel) {
    val check by vm.calendarCheck.collectAsStateWithLifecycle()
    val fields by vm.fieldErrors.collectAsStateWithLifecycle()
    OutlinedTextField(cal.name.orEmpty(), { v -> vm.updateCalendar { it.copy(name = v) } }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.calendar_name)) }, singleLine = true)
    OutlinedTextField(cal.url.orEmpty(), { v -> vm.updateCalendar { it.copy(url = v) } }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.calendar_url)) }, singleLine = true, isError = fields["url"] != null,
        supportingText = { Text(fields["url"]?.joinToString() ?: stringResource(R.string.calendar_url_help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(cal.timezone.orEmpty(), { v -> vm.updateCalendar { it.copy(timezone = v) } }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.calendar_timezone)) }, singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BusyButton(stringResource(R.string.calendar_check), check is CalendarCheck.Running, vm::checkCalendar,
            enabled = !cal.url.isNullOrBlank())
        when (val c = check) {
            is CalendarCheck.Ok -> Text(stringResource(R.string.calendar_check_ok, c.events ?: 0), color = StatusColors.Ok)
            is CalendarCheck.Failed -> Text(c.message, color = StatusColors.Bad)
            else -> Unit
        }
    }    // 422 fields.source: iCal nur auf Ansicht 1 (Vertrag 1.2.4)
    fields["source"]?.let { Text(it.joinToString(), color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall) }
}
