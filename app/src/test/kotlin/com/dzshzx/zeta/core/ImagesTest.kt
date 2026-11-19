package com.dzshzx.zeta.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ImagesTest {
    @Test
    fun fitsWithinTheLongestEdgeRounding() {
        assertEquals(470 to 1024, Images.fitWithin(1220, 2656, 1024))
        assertEquals(800 to 600, Images.fitWithin(800, 600, 1024))
        assertEquals(1568 to 1176, Images.fitWithin(4000, 3000, 1568))
        // 四舍五入：200 × 100/300 = 66.7 → 67（截断会得 66）
        assertEquals(100 to 67, Images.fitWithin(300, 200, 100))
        assertEquals(1 to 100, Images.fitWithin(2, 1000, 100))
    }

    @Test
    fun decodesStraightToTheTargetSize() {
        val decoded = Images.decodeWithin(png(Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }), 100)
        assertEquals(300, decoded.sourceWidth)
        assertEquals(200, decoded.sourceHeight)
        assertEquals(100, decoded.bitmap.width)
        assertEquals(67, decoded.bitmap.height)
        val small = Images.decodeWithin(png(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)), 100)
        assertEquals(40 to 30, small.bitmap.width to small.bitmap.height)
    }

    @Test
    fun scalesOnlyWhenTooLarge() {
        val small = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        assertSame(small, Images.scaleWithin(small, 100))
        val scaled = Images.scaleWithin(Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888), 100)
        assertEquals(100 to 67, scaled.width to scaled.height)
    }

    @Test
    fun transparentPixelsBecomeWhiteInJpeg() {
        val clear = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        assertTrue(clear.hasAlpha())
        val pixel = jpegPixel(Images.jpeg(clear, 80))
        // JPEG 有损，按接近白色判断；不垫底时是黑色
        assertTrue("0x" + Integer.toHexString(pixel), Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun opaqueImagesKeepTheirColours() {
        val blue = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE); setHasAlpha(false) }
        assertFalse(blue.hasAlpha())
        val pixel = jpegPixel(Images.jpeg(blue, 90))
        assertTrue("0x" + Integer.toHexString(pixel), Color.blue(pixel) > 200 && Color.red(pixel) < 40 && Color.green(pixel) < 40)
    }

    @Test
    fun imageTypesAreSniffed() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertEquals("image/jpeg", imageMediaType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", imageMediaType(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A)))
        assertEquals("image/gif", imageMediaType("GIF89a".toByteArray()))
        assertEquals("image/webp", imageMediaType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(imageMediaType("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertNull(imageMediaType(ByteArray(0)))
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun jpegPixel(jpeg: ByteArray): Int {
        assertEquals("image/jpeg", imageMediaType(jpeg))
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).getPixel(4, 4)
    }
}
