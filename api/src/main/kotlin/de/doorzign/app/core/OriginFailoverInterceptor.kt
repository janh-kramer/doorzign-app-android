package de.doorzign.app.core

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Verbund mit mehreren Adressen: Anfragen gehen an die bevorzugte Adresse. Ist sie nicht erreichbar
 * (Verbindungsaufbau scheitert), werden die übrigen der Reihe nach probiert; die erfolgreiche wird bevorzugt.
 * Bei Antworten (auch Fehlern) wird nicht gewechselt.
 */
class OriginFailoverInterceptor(
    origins: List<String>,
    private val onPreferredChanged: (String) -> Unit = {},
) : Interceptor {
    @Volatile private var order: List<String> = origins

    val preferred: String get() = order.first()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        var last: IOException? = null
        for (origin in order) {
            val o = origin.toHttpUrl()
            val url = original.url.newBuilder().scheme(o.scheme).host(o.host).port(o.port).build()
            try {
                val resp = chain.proceed(original.newBuilder().url(url).build())
                if (origin != order.first()) {
                    order = listOf(origin) + order.filter { it != origin }
                    onPreferredChanged(origin)
                }
                return resp
            } catch (e: IOException) {
                if (!isUnreachable(e) || chain.call().isCanceled()) throw e
                last = e
            }
        }
        throw last ?: IOException("Keine Adresse erreichbar")
    }

    private fun isUnreachable(e: IOException) =
        e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
}
