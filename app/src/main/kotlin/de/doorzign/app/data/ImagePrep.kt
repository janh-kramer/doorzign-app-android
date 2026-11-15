package de.doorzign.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bild für den Upload vorbereiten (ui-spec §4): beliebiges Format inkl. HEIC → JPEG, max. 2400 px Kantenlänge,
 * EXIF-Drehung anwenden, Größe gegen `System.mediaMaxBytes` prüfen (Qualität notfalls reduzieren).
 */
object ImagePrep {
    const val MAX_EDGE = 2400

    class TooLarge : Exception("too large")
    class Unreadable : Exception("unreadable")

    suspend fun prepare(context: Context, uri: Uri, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw Unreadable()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = cr.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw Unreadable()
        val rotation = runCatching {
            cr.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees }
        }.getOrDefault(0)

        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        val bmp = if (scale < 1f || rotation != 0) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true) else decoded

        var quality = 90
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return@withContext out.toByteArray()
            if (quality <= 50) throw TooLarge()
            quality -= 10
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }
}
