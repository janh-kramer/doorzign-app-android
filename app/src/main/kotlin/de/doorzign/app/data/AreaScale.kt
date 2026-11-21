package de.doorzign.app.data

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vorschau-PNGs flächenmittelnd verkleinern (ui-spec „Skalierung der Vorschau-PNGs“, decisions 47): jedes Zielpixel ist
 * der Mittelwert der Quellfläche, die es überdeckt (Box-/Area-Filter, auch bei nicht ganzzahligem Verhältnis). Nie
 * Subsampling oder Nearest-Neighbor – sonst wird das Dithering der Displays zu Rauschen. Vergrößert wird hier nicht.
 */
object AreaScale {
    /** Zielgröße für „Einpassen“ in maxW×maxH; nie größer als die Quelle. */
    fun fitSize(sw: Int, sh: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        val scale = min(maxW.toDouble() / sw, maxH.toDouble() / sh)
        if (scale >= 1.0) return sw to sh
        return max(1, (sw * scale).roundToInt()) to max(1, (sh * scale).roundToInt())
    }

    /** ARGB-Pixel (Zeilen hintereinander) von sw×sh auf dw×dh flächenmittelnd verkleinern. */
    fun downscale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        require(src.size == sw * sh) { "Pixelanzahl passt nicht" }
        require(dw in 1..sw && dh in 1..sh) { "Nur Verkleinern" }
        if (dw == sw && dh == sh) return src.copyOf()
        val tx = taps(sw, dw)
        val ty = taps(sh, dh)
        // waagerecht: sw×sh → dw×sh (4 Kanäle als Float)
        val mid = FloatArray(dw * sh * 4)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                val first = tx.first[x]
                val w = tx.weights[x]
                for (k in w.indices) {
                    val c = src[row + first + k]
                    val wk = w[k]
                    a += wk * (c ushr 24); r += wk * ((c shr 16) and 0xFF); g += wk * ((c shr 8) and 0xFF); b += wk * (c and 0xFF)
                }
                val o = (y * dw + x) * 4
                mid[o] = a; mid[o + 1] = r; mid[o + 2] = g; mid[o + 3] = b
            }
        }
        // senkrecht: dw×sh → dw×dh
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val first = ty.first[y]
            val w = ty.weights[y]
            for (x in 0 until dw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in w.indices) {
                    val o = ((first + k) * dw + x) * 4
                    val wk = w[k]
                    a += wk * mid[o]; r += wk * mid[o + 1]; g += wk * mid[o + 2]; b += wk * mid[o + 3]
                }
                out[y * dw + x] = (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
        }
        return out
    }

    private fun ch(v: Float) = v.roundToInt().coerceIn(0, 255)

    private class Taps(val first: IntArray, val weights: Array<FloatArray>)

    /** Für jedes Zielpixel: erstes Quellpixel und Anteile der überdeckten Quellpixel (Summe 1). */
    private fun taps(s: Int, d: Int): Taps {
        val scale = s.toDouble() / d
        val first = IntArray(d)
        val weights = Array(d) { i ->
            val a = i * scale
            val b = (i + 1) * scale
            val lo = floor(a).toInt()
            val hi = min(s, ceil(b - 1e-9).toInt())
            first[i] = lo
            FloatArray(hi - lo) { k -> ((min(b, lo + k + 1.0) - max(a, (lo + k).toDouble())) / scale).toFloat() }
        }
        return Taps(first, weights)
    }

    /** Bitmap in maxW×maxH einpassen (nur verkleinern). */
    fun fit(input: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val (dw, dh) = fitSize(input.width, input.height, maxW, maxH)
        if (dw == input.width && dh == input.height) return input
        val src = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val out = downscale(px, src.width, src.height, dw, dh)
        return Bitmap.createBitmap(out, dw, dh, Bitmap.Config.ARGB_8888)
    }

    /**
     * Coil-Transformation: Die Anfrage lädt in Originalgröße (`Size.ORIGINAL`, kein Subsampling beim Dekodieren),
     * hier wird auf die gemessenen Zielpixel eingepasst.
     */
    class Fit(private val maxW: Int, private val maxH: Int) : Transformation() {
        override val cacheKey = "area-fit-${maxW}x$maxH"
        override suspend fun transform(input: Bitmap, size: Size): Bitmap = fit(input, maxW, maxH)
    }
}
