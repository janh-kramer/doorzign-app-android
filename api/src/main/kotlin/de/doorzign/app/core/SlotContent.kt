package de.doorzign.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Inhalt einer Ansicht (neutrales Slot-Format). Diskriminator ist das Feld `type`. */
@Serializable
sealed interface SlotContent {
    @Serializable @SerialName("text")
    data class Text(
        val title: String = "",
        val headline: String = "",
        val subHeadline: String = "",
        val message: String = "",
        val color: Color? = null,
        val bg: Color? = null,
        val messageColor: Color? = null,
        val messageBg: Color? = null,
    ) : SlotContent

    @Serializable @SerialName("image")
    data class Image(
        val mediaId: String?,
        val fit: Fit = Fit.CROP,
        val background: Color? = null,
        val dither: Boolean = true,
    ) : SlotContent

    @Serializable @SerialName("calendar")
    data class Calendar(
        val source: Source,
        val url: String? = null,
        val name: String? = null,
        val timezone: String? = null,
        /** Darstellung – in v1 unverändert durchreichen. */
        val view: JsonObject? = null,
    ) : SlotContent {
        @Serializable enum class Source { @SerialName("ical") ICAL, @SerialName("zeitwart") ZEITWART }
    }

    @Serializable @SerialName("deactivated")
    data object Deactivated : SlotContent

    @Serializable enum class Color { @SerialName("black") BLACK, @SerialName("white") WHITE, @SerialName("red") RED }
    @Serializable enum class Fit { @SerialName("fill") FILL, @SerialName("crop") CROP }
}

/**
 * `TextContent` außerhalb von [SlotContent] (Ad-hoc-Nachricht). Dort gibt es keinen polymorphen Kontext,
 * daher wird `type` als normales Feld mitgeschickt (Pflichtfeld laut Vertrag).
 */
@Serializable
data class TextContent(
    val type: String = "text",
    val title: String = "",
    val headline: String = "",
    val subHeadline: String = "",
    val message: String = "",
    val color: SlotContent.Color? = null,
    val bg: SlotContent.Color? = null,
    val messageColor: SlotContent.Color? = null,
    val messageBg: SlotContent.Color? = null,
) {
    fun toSlot() = SlotContent.Text(title, headline, subHeadline, message, color, bg, messageColor, messageBg)

    companion object {
        fun of(t: SlotContent.Text) = TextContent("text", t.title, t.headline, t.subHeadline, t.message, t.color, t.bg, t.messageColor, t.messageBg)
    }
}
