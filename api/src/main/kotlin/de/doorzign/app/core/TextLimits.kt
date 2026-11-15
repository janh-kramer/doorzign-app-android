package de.doorzign.app.core

/**
 * maxLength-Grenzen von TextContent laut Vertrag. Gelesene Inhalte dürfen länger sein
 * (Edge-Web erlaubt mehr) – die App nimmt sie an und weist erst beim Bearbeiten darauf hin.
 */
object TextLimits {
    const val TITLE = 50
    const val HEADLINE = 75
    const val SUB_HEADLINE = 50
    const val MESSAGE = 100

    /** Feldnamen (wie in `error.fields`) mit ihrem Grenzwert, deren Text zu lang ist. */
    fun exceeded(title: String, headline: String, subHeadline: String, message: String): Map<String, Int> = buildMap {
        if (title.length > TITLE) put("title", TITLE)
        if (headline.length > HEADLINE) put("headline", HEADLINE)
        if (subHeadline.length > SUB_HEADLINE) put("subHeadline", SUB_HEADLINE)
        if (message.length > MESSAGE) put("message", MESSAGE)
    }

    fun exceeded(t: SlotContent.Text) = exceeded(t.title, t.headline, t.subHeadline, t.message)
}
