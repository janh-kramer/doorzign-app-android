package de.doorzign.app.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Flächenmittelndes Verkleinern der Vorschau-PNGs (decisions 47). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AreaScaleTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** 1-Pixel-Schachbrett wie ein geditherter 50-%-Ton. */
    private fun checker(w: Int, h: Int) = IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) black else white }

    private fun assertGray(px: IntArray, expected: Int, tolerance: Int) {
        for (c in px) {
            assertEquals("deckend", 255, c ushr 24)
            for (v in listOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) {
                assertTrue("Kanal $v statt ≈ $expected", abs(v - expected) <= tolerance)
            }
        }
    }

    @Test fun schachbrettAufEinViertelErgibtGrau() {
        val out = AreaScale.downscale(checker(400, 300), 400, 300, 100, 75)
        assertGray(out, 128, 1)
    }

    @Test fun schachbrettBeiKrummemVerhaeltnisBleibtGrau() {
        // 800×600 → 288×216 (Displayliste: 96 dp bei 3×): kein Moiré, nur kleine Abweichung durch Teilflächen
        val out = AreaScale.downscale(checker(800, 600), 800, 600, 288, 216)
        assertGray(out, 128, 12)
    }

    @Test fun halbierenMitteltGenauVierPixel() {
        val src = intArrayOf(
            0xFF000000.toInt(), 0xFFFF0000.toInt(),
            0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
        )
        val c = AreaScale.downscale(src, 2, 2, 1, 1)[0]
        assertEquals(64, (c shr 16) and 0xFF)
        assertEquals(64, (c shr 8) and 0xFF)
        assertEquals(64, c and 0xFF)
    }

    @Test fun kanteBleibtAnDerRichtigenStelle() {
        // links schwarz, rechts weiß: nach 1/4 bleibt die linke Hälfte schwarz und die rechte weiß
        val src = IntArray(400 * 4) { i -> if (i % 400 < 200) black else white }
        val out = AreaScale.downscale(src, 400, 4, 100, 1)
        assertEquals(black, out[0]); assertEquals(black, out[49])
        assertEquals(white, out[50]); assertEquals(white, out[99])
    }

    @Test fun einpassenVergroessertNie() {
        assertEquals(800 to 600, AreaScale.fitSize(800, 600, 1200, 900))
        assertEquals(288 to 216, AreaScale.fitSize(800, 600, 288, 216))
        assertEquals(288 to 216, AreaScale.fitSize(800, 600, 288, 400)) // Breite begrenzt, Seitenverhältnis bleibt
    }

    @Test fun bitmapSchachbrettWirdGrau() {
        val src = Bitmap.createBitmap(checker(400, 300), 400, 300, Bitmap.Config.ARGB_8888)
        val out = AreaScale.fit(src, 100, 100)
        assertEquals(100, out.width)
        assertEquals(75, out.height)
        val c = out.getPixel(50, 37)
        assertTrue(abs(Color.red(c) - 128) <= 1 && abs(Color.green(c) - 128) <= 1 && abs(Color.blue(c) - 128) <= 1)
    }
}
