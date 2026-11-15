package de.doorzign.app.core

import okhttp3.Interceptor
import okhttp3.Response

/** Daten der App für Header und Anmeldung. */
data class AppInfo(
    val version: String,
    val platform: String = "android",
    /** Gerätesprache für `Accept-Language` (`de` oder `en`); wird je Request gelesen, damit ein Sprachwechsel sofort wirkt. */
    private val languageOf: () -> String = { "de" },
) {
    val language: String get() = languageOf()
    val userAgent: String get() = "DoorZign-App/$version ($platform)"
}

/** Setzt Bearer-Token (außer für öffentliche Endpunkte), Accept-Language und User-Agent. */
class AuthInterceptor(
    private val tokens: TokenStore,
    private val app: AppInfo,
    private val apiPath: String = ConnectionProfile.API_PATH,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val b = req.newBuilder()
            .header("Accept-Language", app.language)
            .header("User-Agent", app.userAgent)
        if (req.header("Authorization") == null && !isPublic(req.url.encodedPath, apiPath)) {
            tokens.load()?.let { b.header("Authorization", "Bearer ${it.accessToken}") }
        }
        return chain.proceed(b.build())
    }

    companion object {
        private val PUBLIC = setOf("/system", "/auth/providers", "/auth/login", "/auth/token", "/auth/refresh", "/auth/sso/start")

        fun isPublic(path: String, apiPath: String = ConnectionProfile.API_PATH): Boolean {
            val idx = if (apiPath.isEmpty()) -1 else path.indexOf(apiPath)
            val rel = if (idx >= 0) path.substring(idx + apiPath.length) else path
            return rel in PUBLIC
        }
    }
}
