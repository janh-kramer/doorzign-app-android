package de.doorzign.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dreh-Schritt (decisions 44): EXIF in die Pixel, Nutzerdrehung, PNG bleibt PNG. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImagePrepTest {
    private val max = 5_000_000

    /** Zwei Hälften: [first] links bzw. oben, [second] rechts bzw. unten. */
    private fun halves(w: Int, h: Int, vertical: Boolean, first: Int, second: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        p.color = first
        if (vertical) c.drawRect(0f, 0f, w / 2f, h.toFloat(), p) else c.drawRect(0f, 0f, w.toFloat(), h / 2f, p)
        p.color = second
        if (vertical) c.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), p) else c.drawRect(0f, h / 2f, w.toFloat(), h.toFloat(), p)
        return b
    }

    private fun encode(b: Bitmap, fmt: Bitmap.CompressFormat) = ByteArrayOutputStream().also { b.compress(fmt, 95, it) }.toByteArray()

    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val f = File.createTempFile("exif", ".jpg").apply { writeBytes(jpeg) }
        ExifInterface(f).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return f.readBytes().also { f.delete() }
    }

    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.blue(c) < 60
    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60

    private fun orientationTag(bytes: ByteArray) =
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

    @Test
    fun portraitWithExif6ArrivesUprightWithoutRotation() = runBlocking {
        // Aufrecht: 300×400, oben rot, unten blau. Sensor speichert 90° gegen den Uhrzeigersinn: 400×300, links rot.
        val stored = withOrientation(encode(halves(400, 300, vertical = true, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.JPEG),
            ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, orientationTag(stored))

        val p = ImagePrep.prepare(stored, turns = 0, maxBytes = max)
        assertEquals("image/jpeg", p.mime)
        val out = decode(p.bytes)
        assertEquals(300, out.width)
        assertEquals(400, out.height)
        assertTrue("oben rot", isRed(out.getPixel(150, 50)))
        assertTrue("unten blau", isBlue(out.getPixel(150, 350)))
        val tag = orientationTag(p.bytes)
        assertTrue("kein Orientation-Tag (oder 1), war $tag", tag == ExifInterface.ORIENTATION_UNDEFINED || tag == ExifInterface.ORIENTATION_NORMAL)
    }

    @Test
    fun rotateRightOnceSwapsWidthAndHeight() = runBlocking {
        // 400×300, oben rot. Rechts drehen (90° im Uhrzeigersinn): 300×400, rot rechts.
        val jpeg = encode(halves(400, 300, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.JPEG)
        val p = ImagePrep.prepare(jpeg, turns = 1, maxBytes = max)
        val out = decode(p.bytes)
        assertEquals(300, out.width)
        assertEquals(400, out.height)
        assertTrue("rechts rot", isRed(out.getPixel(250, 200)))
        assertTrue("links blau", isBlue(out.getPixel(50, 200)))
    }

    @Test
    fun rotateLeftIsThreeQuarterTurns() = runBlocking {
        val jpeg = encode(halves(400, 300, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.JPEG)
        val out = decode(ImagePrep.prepare(jpeg, turns = -1, maxBytes = max).bytes)
        assertEquals(300, out.width)
        assertTrue("links rot", isRed(out.getPixel(50, 200)))
    }

    @Test
    fun jpegIsReencodedEvenWithoutRotation() = runBlocking {
        val jpeg = encode(halves(400, 300, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.JPEG)
        val p = ImagePrep.prepare(jpeg, turns = 0, maxBytes = max)
        assertEquals("image/jpeg", p.mime)
        assertTrue(!p.bytes.contentEquals(jpeg))
    }

    @Test
    fun unrotatedPngStaysOriginal() = runBlocking {
        val png = encode(halves(400, 300, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.PNG)
        val p = ImagePrep.prepare(png, turns = 0, maxBytes = max)
        assertEquals("image/png", p.mime)
        assertEquals("bild.png", p.fileName)
        assertArrayEquals(png, p.bytes)
    }

    @Test
    fun rotatedPngStaysPng() = runBlocking {
        val png = encode(halves(400, 300, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.PNG)
        val p = ImagePrep.prepare(png, turns = 1, maxBytes = max)
        assertEquals("image/png", p.mime)
        val out = decode(p.bytes)
        assertEquals(300, out.width)
        assertEquals(400, out.height)
        assertTrue("rechts rot", isRed(out.getPixel(250, 200)))
    }

    @Test
    fun largeImageIsScaledTo2400() = runBlocking {
        val jpeg = encode(halves(4000, 3000, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.JPEG)
        val out = decode(ImagePrep.prepare(jpeg, turns = 1, maxBytes = max).bytes)
        assertEquals(2400, out.height)
        assertEquals(1800, out.width)
    }

    @Test
    fun pngThatDoesNotFitBecomesJpeg() = runBlocking {
        // Rauschen: als PNG groß, als JPEG deutlich kleiner
        val rnd = java.util.Random(1)
        val b = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        for (y in 0 until 400) for (x in 0 until 600) b.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        val png = encode(b, Bitmap.CompressFormat.PNG)
        val limit = png.size / 2
        val p = ImagePrep.prepare(png, turns = 1, maxBytes = limit)
        assertEquals("image/jpeg", p.mime)
        assertTrue(p.bytes.size <= limit)
        assertEquals(400, decode(p.bytes).width)
    }

    @Test(expected = ImagePrep.TooLarge::class)
    fun tooLargeAfterQualityReduction() {
        val jpeg = encode(halves(400, 300, vertical = false, first = Color.RED, second = Color.BLUE), Bitmap.CompressFormat.JPEG)
        runBlocking { ImagePrep.prepare(jpeg, turns = 0, maxBytes = 100) }
    }
}
