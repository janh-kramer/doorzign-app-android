package de.doorzign.app.core

/**
 * Was mit einem geöffneten Verbindungslink (`doorzign://edge|cloud…`) passiert (ui-spec §1 „Verbindungslinks“, decisions 45).
 * Reine Entscheidung; ausgeführt wird sie im SessionManager der App.
 */
object LinkRouting {
    enum class Where { ONBOARDING, SIGN_IN_PAGE, SIGNED_IN }

    sealed interface Decision {
        /** Onboarding prüft den Link selbst (auch ungültige: der aktuelle Schritt zeigt den Fehler). */
        data object ToOnboarding : Decision
        /** Anmeldeseite, gültiger Link: ohne Rückfrage abmelden und den Link ans Onboarding geben. */
        data object DisconnectAndTake : Decision
        /** Anmeldeseite, ungültiger Link: Fehler anzeigen, Verbindung bleibt. */
        data class ShowError(val error: PairingException) : Decision
        /** Angemeldet, anderes System: Bestätigung „Mit anderem System verbinden“. */
        data object ConfirmSwitch : Decision
        /** Angemeldet, gleiches System oder ungültig: ohne Folgen verwerfen. */
        data object Drop : Decision
    }

    fun decide(raw: String, where: Where, current: ConnectionProfile?): Decision {
        if (where == Where.ONBOARDING || current == null) return Decision.ToOnboarding
        val link = try {
            PairingUri.parse(raw)
        } catch (e: PairingException) {
            return if (where == Where.SIGN_IN_PAGE) Decision.ShowError(e) else Decision.Drop
        }
        return when (where) {
            Where.SIGN_IN_PAGE -> Decision.DisconnectAndTake
            else -> if (isSameSystem(link, current)) Decision.Drop else Decision.ConfirmSwitch
        }
    }

    /** Edge: gleiche `instanceId`; Cloud: gleiche Basis-URL. */
    fun isSameSystem(link: PairingUri, current: ConnectionProfile): Boolean = when (link) {
        is PairingUri.Edge -> current.systemType == SystemType.EDGE && link.instanceId == current.instanceId
        is PairingUri.Cloud -> current.systemType == SystemType.CLOUD && current.origins.any {
            runCatching { PairingUri.normalizeOrigin(it) }.getOrNull() == link.origin
        }
    }
}
