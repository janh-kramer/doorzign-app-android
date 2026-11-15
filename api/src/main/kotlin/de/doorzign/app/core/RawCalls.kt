package de.doorzign.app.core

import de.doorzign.app.api.infrastructure.Serializer
import de.doorzign.app.api.model.Media
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Aufrufe, die der Generator nicht korrekt abbildet:
 * - `POST /media`: Ab Vertrag 1.2 (`contentMediaType`) erzeugt openapi-generator `file: String` und einen
 *   ungültigen Part-Header mit Typliste. Hier: echter Binär-Part mit dem Bildtyp als Content-Type.
 * - `POST …/preview`: liefert PNG-Bytes direkt statt einer temporären Datei.
 */
private val json get() = Serializer.kotlinxSerializationJson

suspend fun Session.uploadMedia(bytes: ByteArray, mime: String, fileName: String): Media = apiCall {
    require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Nicht unterstützter Bildtyp: $mime" }
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
        .build()
    val req = Request.Builder().url("${profile.apiBase}/media").header("Accept", "application/json").post(body).build()
    client.newCall(req).execute().use { r -> json.decodeFromString(Media.serializer(), r.successBody()) }
}

suspend fun Session.renderPreview(displayId: String, slot: Int, content: SlotContent, state: String? = null): ByteArray = apiCall {
    val url = StringBuilder("${profile.apiBase}/displays/${encode(displayId)}/slots/$slot/preview")
    if (state != null) url.append("?state=").append(state)
    val body = json.encodeToString(SlotContent.serializer(), content).toRequestBody("application/json".toMediaType())
    val req = Request.Builder().url(url.toString()).header("Accept", "image/png").post(body).build()
    client.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw ApiException.fromBody(r.code, r.body?.string())
        r.body!!.bytes()
    }
}

private fun Response.successBody(): String {
    val text = body?.string().orEmpty()
    if (!isSuccessful) throw ApiException.fromBody(code, text)
    return text
}

private fun encode(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
