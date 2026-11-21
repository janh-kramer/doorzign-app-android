package de.doorzign.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bild für den Upload vorbereiten (ui-spec §4 „Bild“, decisions 44): zuerst die EXIF-Ausrichtung anwenden, dann die
 * Nutzerdrehung (Vierteldrehungen im Uhrzeigersinn) in die Pixel schreiben. JPEG/HEIC/WebP werden immer als JPEG ohne
 * Orientation-Tag neu kodiert. PNG bleibt PNG (ein ungedrehtes PNG innerhalb der Grenzen geht als Original hoch) und wird
 * nur zu JPEG, wenn es als PNG nicht in `mediaMaxBytes` passt.
 * Max. 2400 px Kantenlänge, Größe gegen `System.mediaMaxBytes` (JPEG-Qualität notfalls reduzieren).
 */
object ImagePrep {
    const val MAX_EDGE = 2400
    const val PREVIEW_EDGE = 1080

    class TooLarge : Exception("too large")
    class Unreadable : Exception("unreadable")

    class Prepared(val bytes: ByteArray, val mime: String, val fileName: String)

    /** Originaldatei lesen (Kamera/Galerie); die Bytes bleiben bis „Hochladen“/„Abbrechen“ im Dreh-Schritt. */
    suspend fun read(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Unreadable()
    }

    /** Vorschau für den Dreh-Schritt: EXIF angewendet, auf [PREVIEW_EDGE] verkleinert. */
    suspend fun preview(bytes: ByteArray): Bitmap = withContext(Dispatchers.Default) { decodeOriented(bytes, PREVIEW_EDGE).first }

    /** Vorschau um [turns] Vierteldrehungen im Uhrzeigersinn drehen. */
    fun rotated(bmp: Bitmap, turns: Int): Bitmap {
        val t = Math.floorMod(turns, 4)
        if (t == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { setRotate(90f * t) }, true)
    }

    suspend fun prepare(bytes: ByteArray, turns: Int, maxBytes: Int): Prepared = withContext(Dispatchers.Default) {
        val t = Math.floorMod(turns, 4)
        val (oriented, mime) = decodeOriented(bytes, MAX_EDGE)
        val png = mime == "image/png"
        if (png && t == 0 && maxOf(oriented.width, oriented.height) <= MAX_EDGE && bytes.size <= maxBytes) {
            return@withContext Prepared(bytes, "image/png", "bild.png")
        }
        val bmp = rotated(oriented, t)
        if (png) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (out.size() <= maxBytes) return@withContext Prepared(out.toByteArray(), "image/png", "bild.png")
            // passt als PNG nicht in mediaMaxBytes → JPEG (ui-spec „Bild“)
        }
        var quality = 90
        while (true) {
            val out = ByteArrayOutputStream()
            // Bitmap.compress schreibt kein EXIF – das JPEG hat damit keinen Orientation-Tag
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return@withContext Prepared(out.toByteArray(), "image/jpeg", "foto.jpg")
            if (quality <= 50) throw TooLarge()
            quality -= 10
        }
        @Suppress("UNREACHABLE_CODE") throw TooLarge()
    }

    /** Dekodieren (mit inSampleSize), EXIF-Ausrichtung anwenden, auf [maxEdge] verkleinern. Liefert Bitmap + MIME-Typ. */
    internal fun decodeOriented(bytes: ByteArray, maxEdge: Int): Pair<Bitmap, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Unreadable()
        val mime = bounds.outMimeType ?: "image/jpeg"
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Unreadable()
        val orientation = if (mime == "image/png") ExifInterface.ORIENTATION_NORMAL else runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
        val m = exifMatrix(orientation)
        if (scale < 1f) m.postScale(scale, scale)
        val bmp = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        return bmp to mime
    }

    /** EXIF-Orientation 1–8 als Matrix (inkl. Spiegelungen). */
    private fun exifMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
