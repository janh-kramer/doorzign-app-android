package de.doorzign.app.ui.displays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.NetworkWifi2Bar
import androidx.compose.material.icons.filled.NetworkWifi1Bar
import androidx.compose.material.icons.filled.SignalWifiStatusbarNull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import de.doorzign.app.R
import de.doorzign.app.api.model.Display
import de.doorzign.app.core.BatteryState
import de.doorzign.app.core.DisplayStatus
import de.doorzign.app.core.SignalQuality
import de.doorzign.app.data.Connection
import de.doorzign.app.ui.theme.StatusColors
import java.time.Duration
import java.time.Instant

/** URL der gespeicherten Vorschau einer Ansicht. */
fun Connection.previewUrl(displayId: String, slot: Int) = "${profile.apiBase}/displays/$displayId/slots/$slot/preview.png"

/**
 * Vorschau-PNG mit Cache-Schlüssel aus dem ETag: neues ETag → neu laden, sonst aus dem Cache.
 * Vertrag 1.2.3: `previewEtag == null` genau dann, wenn preview.png 404 liefert (deaktivierte Ansicht,
 * Bildansicht ohne Bild) – dann wird nichts geladen, sondern ein Platzhalter gezeigt.
 */
@Composable
fun SlotPreview(conn: Connection, displayId: String, slot: Int, etag: String?, modifier: Modifier = Modifier) {
    val shaped = modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFEEEEEE))
    val cd = stringResource(R.string.slot_preview_cd, slot)
    val tag = normalizeEtag(etag)
    if (tag == null) {
        Box(shaped.semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ImageNotSupported, null, tint = StatusColors.Neutral)
                Text(stringResource(R.string.slot_no_preview), style = MaterialTheme.typography.labelSmall, color = StatusColors.Neutral)
            }
        }
        return
    }
    val url = conn.previewUrl(displayId, slot)
    val key = "$url#$tag"
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).memoryCacheKey(key).diskCacheKey(key)
            .placeholderMemoryCacheKey(lastKeys[url]).listener(onSuccess = { _, _ -> lastKeys[url] = key }).build(),
        imageLoader = conn.imageLoader,
        contentDescription = cd,
        contentScale = ContentScale.Fit,
        modifier = shaped,
    )
}

@Composable
fun StatusChip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun OnlineChip(online: Boolean) =
    if (online) StatusChip(stringResource(R.string.status_online), StatusColors.Ok)
    else StatusChip(stringResource(R.string.status_offline), StatusColors.Neutral)

@Composable
fun BatteryLabel(percent: Int?) {
    val state = DisplayStatus.battery(percent)
    val (icon, color) = when (state) {
        BatteryState.CRITICAL -> Icons.Default.Battery1Bar to StatusColors.Bad
        BatteryState.LOW -> Icons.Default.Battery4Bar to StatusColors.Warn
        BatteryState.NORMAL -> Icons.Default.BatteryFull to MaterialTheme.colorScheme.onSurfaceVariant
        BatteryState.UNKNOWN -> Icons.Default.BatteryUnknown to StatusColors.Neutral
    }
    val text = when (state) {
        BatteryState.UNKNOWN -> stringResource(R.string.status_unknown)
        BatteryState.CRITICAL -> stringResource(R.string.battery_critical, percent ?: 0)
        else -> "$percent %"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, stringResource(R.string.battery_cd), Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun SignalLabel(rssi: Int?) {
    val q = DisplayStatus.signal(rssi)
    val (icon, text, color) = when (q) {
        SignalQuality.GOOD -> Triple(Icons.Default.SignalWifi4Bar, stringResource(R.string.signal_good), MaterialTheme.colorScheme.onSurfaceVariant)
        SignalQuality.MEDIUM -> Triple(Icons.Default.NetworkWifi2Bar, stringResource(R.string.signal_medium), MaterialTheme.colorScheme.onSurfaceVariant)
        SignalQuality.WEAK -> Triple(Icons.Default.NetworkWifi1Bar, stringResource(R.string.signal_weak), StatusColors.Warn)
        SignalQuality.UNKNOWN -> Triple(Icons.Default.SignalWifiStatusbarNull, stringResource(R.string.status_unknown), StatusColors.Neutral)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, stringResource(R.string.signal_cd), Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** „vor 5 Min.“ usw. für `lastSeen`. */
@Composable
fun relativeTime(iso: String?): String {
    val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return stringResource(R.string.status_unknown)
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 1 -> stringResource(R.string.time_just_now)
        d.toMinutes() < 60 -> stringResource(R.string.time_minutes_ago, d.toMinutes())
        d.toHours() < 48 -> stringResource(R.string.time_hours_ago, d.toHours())
        else -> stringResource(R.string.time_days_ago, d.toDays())
    }
}

/** `W/"abc"` und `"abc"` → `abc` (Vertrag 1.2.2: previewEtag = ETag-Header inkl. Anführungszeichen). */
fun normalizeEtag(etag: String?): String? =
    etag?.trim()?.removePrefix("W/")?.trim('"')?.takeIf { it.isNotEmpty() }

/** Letzter erfolgreich geladener Schlüssel je URL – als Platzhalter, damit beim Neuladen nichts flackert. */
private val lastKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

fun Display.activePreviewEtag(): String? = slots.firstOrNull { it.slot == activeSlot }?.previewEtag
