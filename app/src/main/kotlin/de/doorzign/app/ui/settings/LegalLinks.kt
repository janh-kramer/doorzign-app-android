package de.doorzign.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Rechtliche Links – einzige Stelle in der App, bewusst nicht aus dem Vertrag. */
object LegalLinks {
    // TODO: von Jan bestätigen
    const val IMPRINT = "https://doorzign.de/impressum"

    // TODO: von Jan bestätigen
    const val PRIVACY = "https://doorzign.de/datenschutz"

    fun open(context: Context, url: String) =
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}
