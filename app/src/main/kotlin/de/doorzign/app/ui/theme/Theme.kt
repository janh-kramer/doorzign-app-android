package de.doorzign.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Akzentfarbe angelehnt an die E-Paper-Farbe Rot. */
private val Red = Color(0xFFC62828)
private val Slate = Color(0xFF37474F)

private val Light = lightColorScheme(primary = Slate, secondary = Red, tertiary = Red)
private val Dark = darkColorScheme(primary = Color(0xFFB0BEC5), secondary = Color(0xFFEF9A9A), tertiary = Color(0xFFEF9A9A))

/** Statusfarben (Online, Warnung, Fehler) unabhängig vom dynamischen Farbschema. */
object StatusColors {
    val Ok = Color(0xFF2E7D32)
    val Warn = Color(0xFFEF6C00)
    val Bad = Color(0xFFC62828)
    val Neutral = Color(0xFF757575)
}

@Composable
fun DoorZignTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
