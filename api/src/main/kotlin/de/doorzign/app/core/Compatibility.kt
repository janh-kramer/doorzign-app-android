package de.doorzign.app.core

enum class Compatibility { OK, UPDATE_APP, INCOMPATIBLE }

object Versions {
    const val SUPPORTED_API_MAJOR = 1

    fun check(apiVersion: String, minAppVersion: String, appVersion: String): Compatibility {
        val major = apiVersion.substringBefore('.').toIntOrNull() ?: return Compatibility.INCOMPATIBLE
        return when {
            major > SUPPORTED_API_MAJOR -> Compatibility.UPDATE_APP
            major < SUPPORTED_API_MAJOR -> Compatibility.INCOMPATIBLE
            compare(minAppVersion, appVersion) > 0 -> Compatibility.UPDATE_APP
            else -> Compatibility.OK
        }
    }

    /** Numerischer Vergleich `1.2.10` > `1.2.9`; Suffixe wie `-beta` werden ignoriert. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}

/** Freischaltung über `System.features` – nie über `systemType`. */
class SystemFeatures(values: Collection<String>) {
    private val set = values.toSet()
    operator fun contains(f: String) = f in set

    val sso get() = AUTH_SSO in set
    val calendarIcal get() = CALENDAR_ICAL in set
    val calendarZeitwart get() = CALENDAR_ZEITWART in set
    val adhoc get() = ADHOC in set
    val schedules get() = SCHEDULES in set
    val identify get() = IDENTIFY in set
    val mediaUpload get() = MEDIA_UPLOAD in set
    val mediaLibrary get() = MEDIA_LIBRARY in set

    companion object {
        const val AUTH_SSO = "auth.sso"
        const val CALENDAR_ICAL = "calendar.ical"
        const val CALENDAR_ZEITWART = "calendar.zeitwart"
        const val ADHOC = "adhoc"
        const val SCHEDULES = "schedules"
        const val IDENTIFY = "identify"
        const val MEDIA_UPLOAD = "media.upload"
        const val MEDIA_LIBRARY = "media.library"
    }
}
