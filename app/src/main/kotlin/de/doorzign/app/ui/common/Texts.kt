package de.doorzign.app.ui.common

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import de.doorzign.app.R
import de.doorzign.app.core.ApiException
import de.doorzign.app.core.PairingException
import de.doorzign.app.core.ScheduleRules
import de.doorzign.app.data.ImagePrep

/**
 * Alle Texte für ViewModels kommen aus `strings.xml`. Serverseitige `error.message`-Texte werden laut Vertrag
 * direkt angezeigt; lokale Fehler (Netzwerk, Zertifikat, Eingaben) werden hier über Codes übersetzt.
 */
object Texts {
    private lateinit var res: Resources

    fun init(context: Context) { res = context.applicationContext.resources }

    fun get(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)

    fun error(t: Throwable): String = when (t) {
        is ApiException -> t.serverMessage ?: local(t)
        is PairingException -> pairing(t.reason)
        is ImagePrep.TooLarge -> get(R.string.err_image_too_large)
        is ImagePrep.Unreadable -> get(R.string.err_image_unreadable)
        else -> get(R.string.err_unknown)
    }

    private fun local(e: ApiException): String = when (e.code) {
        ApiException.NETWORK -> get(R.string.err_network)
        ApiException.TIMEOUT -> get(R.string.err_timeout)
        ApiException.TLS -> get(R.string.err_tls)
        ApiException.CERTIFICATE_MISMATCH -> get(R.string.err_certificate_changed)
        ApiException.INSTANCE_MISMATCH -> get(R.string.err_instance_mismatch)
        ApiException.PROTOCOL -> get(R.string.err_protocol)
        ApiException.UPDATE_APP -> get(R.string.compat_update)
        ApiException.INCOMPATIBLE -> get(R.string.compat_incompatible)
        else -> when (e.status) {
            401 -> get(R.string.err_401)
            403 -> get(R.string.err_403)
            404 -> get(R.string.err_404)
            412 -> get(R.string.editor_conflict_title)
            413 -> get(R.string.err_413)
            429 -> get(R.string.err_429)
            503 -> get(R.string.err_503)
            else -> get(R.string.err_server, e.status)
        }
    }

    fun pairing(r: PairingException.Reason): String = get(when (r) {
        PairingException.Reason.NOT_DOORZIGN -> R.string.pair_not_doorzign
        PairingException.Reason.NO_ADDRESS -> R.string.pair_no_address
        PairingException.Reason.BAD_FINGERPRINT -> R.string.pair_bad_fingerprint
        PairingException.Reason.NO_FINGERPRINT -> R.string.pair_no_fingerprint
        PairingException.Reason.FINGERPRINT_COUNT -> R.string.pair_fingerprint_count
        PairingException.Reason.NO_INSTANCE -> R.string.pair_no_instance
        PairingException.Reason.NOT_A_PAIRING_CODE -> R.string.pair_not_pairing
        PairingException.Reason.UNKNOWN_CODE -> R.string.pair_unknown
        PairingException.Reason.INVALID_ADDRESS -> R.string.pair_invalid_address
        PairingException.Reason.HTTPS_ONLY -> R.string.pair_https_only
        PairingException.Reason.EMPTY_INSTANCE -> R.string.pair_empty_instance
        PairingException.Reason.INVALID_INSTANCE -> R.string.pair_invalid_instance
    })

    fun schedule(e: ScheduleRules.Error): String = get(when (e) {
        ScheduleRules.Error.NO_WEEKDAY -> R.string.sched_err_weekday
        ScheduleRules.Error.TIME_FORMAT -> R.string.sched_err_time
        ScheduleRules.Error.START_EQUALS_END -> R.string.sched_err_equal
        ScheduleRules.Error.PRIORITY_RANGE -> R.string.sched_err_priority
        ScheduleRules.Error.DATE_FORMAT -> R.string.sched_err_date
        ScheduleRules.Error.UNTIL_BEFORE_FROM -> R.string.sched_err_until
    })
}
