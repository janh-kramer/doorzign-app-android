package de.doorzign.app.core

/** Einstufungen laut ui-spec „Grundregeln“. */
enum class SignalQuality { GOOD, MEDIUM, WEAK, UNKNOWN }
enum class BatteryState { NORMAL, LOW, CRITICAL, UNKNOWN }

object DisplayStatus {
    fun signal(rssi: Int?): SignalQuality = when {
        rssi == null -> SignalQuality.UNKNOWN
        rssi >= -65 -> SignalQuality.GOOD
        rssi >= -80 -> SignalQuality.MEDIUM
        else -> SignalQuality.WEAK
    }

    fun battery(percent: Int?): BatteryState = when {
        percent == null -> BatteryState.UNKNOWN
        percent < 15 -> BatteryState.CRITICAL
        percent < 30 -> BatteryState.LOW
        else -> BatteryState.NORMAL
    }
}

/** Stufen pro Display: view ⊂ switch ⊂ edit. */
enum class Level { VIEW, SWITCH, EDIT;
    fun atLeast(other: Level) = ordinal >= other.ordinal
    companion object {
        fun of(raw: String?): Level = when (raw) { "edit" -> EDIT; "switch" -> SWITCH; else -> VIEW }
    }
}

/** Offene Jobs = pending, progress, retry. */
object JobStates {
    val OPEN = setOf("pending", "progress", "retry")
    fun isOpen(status: String) = status in OPEN
}
