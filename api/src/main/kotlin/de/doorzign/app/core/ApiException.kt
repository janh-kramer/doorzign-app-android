package de.doorzign.app.core

import de.doorzign.app.api.infrastructure.ClientError
import de.doorzign.app.api.infrastructure.ClientException
import de.doorzign.app.api.infrastructure.Serializer
import de.doorzign.app.api.infrastructure.ServerError
import de.doorzign.app.api.infrastructure.ServerException
import de.doorzign.app.api.model.ErrorResponse
import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einheitlicher Fehler für die Oberfläche. [code] ist entweder ein Vertragscode (`validation`, `forbidden`, …)
 * oder ein app-interner Code aus [ApiException.Companion].
 *
 * [serverMessage] ist der deutsche Text aus `error.message` des Backends. Fehlt er (Netzwerk, Zertifikat,
 * Antwort ohne Fehlerformat), übersetzt die App [code] bzw. [status] selbst – der Kern enthält keine UI-Texte.
 */
class ApiException(
    val status: Int,
    val code: String,
    val serverMessage: String? = null,
    val fields: Map<String, List<String>> = emptyMap(),
    cause: Throwable? = null,
) : Exception(serverMessage ?: "$code ($status)", cause) {
    companion object {
        const val NETWORK = "network"
        const val TIMEOUT = "timeout"
        const val TLS = "tls"
        const val CERTIFICATE_MISMATCH = "certificate_mismatch"
        const val INSTANCE_MISMATCH = "instance_mismatch"
        const val PROTOCOL = "protocol"
        const val UPDATE_APP = "update_app"
        const val INCOMPATIBLE = "incompatible"

        fun from(t: Throwable): ApiException = when (t) {
            is ApiException -> t
            is ClientException -> fromBody(t.statusCode, (t.response as? ClientError<*>)?.body as? String, t)
            is ServerException -> fromBody(t.statusCode, (t.response as? ServerError<*>)?.body as? String, t)
            is IOException -> fromIo(t)
            is kotlinx.serialization.SerializationException, is IllegalStateException, is UnsupportedOperationException ->
                ApiException(0, PROTOCOL, cause = t)
            else -> ApiException(0, PROTOCOL, cause = t)
        }

        private fun fromIo(t: IOException): ApiException {
            var c: Throwable? = t
            while (c != null) {
                if (c is CertificateMismatchException) {
                    return ApiException(0, CERTIFICATE_MISMATCH, cause = t)
                }
                c = c.cause
            }
            val code = when (t) {
                is InterruptedIOException -> TIMEOUT
                is SSLException -> TLS
                else -> NETWORK
            }
            return ApiException(0, code, cause = t)
        }

        fun fromBody(status: Int, body: String?, cause: Throwable? = null): ApiException {
            val parsed = body?.let { runCatching { Serializer.kotlinxSerializationJson.decodeFromString<ErrorResponse>(it) }.getOrNull() }
            val err = parsed?.error
            return if (err != null) {
                ApiException(status, err.code, err.message, err.fields.orEmpty(), cause)
            } else {
                ApiException(status, fallbackCode(status), cause = cause)
            }
        }

        private fun fallbackCode(status: Int) = when (status) {
            401 -> "unauthorized"; 403 -> "forbidden"; 404 -> "not_found"; 409 -> "conflict"
            412 -> "precondition_failed"; 413 -> "too_large"; 422 -> "validation"; 429 -> "rate_limited"
            503 -> "unavailable"; else -> "internal"
        }

    }
}

/** Führt einen (blockierenden) generierten API-Aufruf im IO-Dispatcher aus und vereinheitlicht Fehler. */
suspend fun <T> apiCall(block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ApiException.from(e)
    }
}
