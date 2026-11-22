package com.dzshzx.zeta.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 看大图时保存、分享的纯逻辑：按文件头认类型、起文件名，拿不到字节或认不出时不给（按钮置灰）。 */
class ImageExportTest {
    private val now = LocalDateTime.of(2026, 11, 22, 9, 5, 7)

    private fun bytes(vararg head: Int) =
        ByteArray(16).also { out -> head.forEachIndexed { i, b -> out[i] = b.toByte() } }

    private fun ascii(text: String, at: Int = 0, size: Int = 16) =
        ByteArray(size).also { text.toByteArray(Charsets.ISO_8859_1).copyInto(it, at) }

    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun mimeFromFileHeader() {
        assertEquals("image/png", ImageExport.mimeOf(png))
        assertEquals("image/jpeg", ImageExport.mimeOf(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", ImageExport.mimeOf(ascii("GIF89a")))
        assertEquals(
            "image/webp",
            ImageExport.mimeOf(ascii("RIFF").also { "WEBP".toByteArray().copyInto(it, 8) }),
        )
        assertEquals("image/bmp", ImageExport.mimeOf(ascii("BM")))
        assertEquals("image/heic", ImageExport.mimeOf(ascii("ftypheic", at = 4)))
        assertEquals("image/avif", ImageExport.mimeOf(ascii("ftypavif", at = 4)))
        assertNull(ImageExport.mimeOf(ascii("<html>")))
        assertNull(ImageExport.mimeOf(byteArrayOf(0x89.toByte())))
    }

    @Test
    fun fileNameKeepsTheOriginalNameWithTheRealExtension() {
        assertEquals(
            "首页截图.png",
            ImageExport.fileName(FileImage("/workspace/demo/首页截图.png"), "image/png", now),
        )
        // 扩展名跟内容走：叫 .png 其实是 JPEG 的，存成 .jpg
        assertEquals(
            "shot.jpg",
            ImageExport.fileName(FileImage("/tmp/shot.png"), "image/jpeg", now),
        )
        assertEquals(
            "diagram.webp",
            ImageExport.fileName(
                RemoteImage("https://example.invalid/a/b/diagram.webp?w=800#top"),
                "image/webp",
                now,
            ),
        )
        assertEquals(
            "a_b_c.png",
            ImageExport.fileName(FileImage("/tmp/a:b*c.png"), "image/png", now),
        )
    }

    @Test
    fun fileNameFallsBackToATimestamp() {
        assertEquals(
            "Zeta_20261122_090507.png",
            ImageExport.fileName(InlineImage("d:1:2", "AAAA"), "image/png", now),
        )
        assertEquals(
            "Zeta_20261122_090507.jpg",
            ImageExport.fileName(RemoteImage("https://example.invalid/"), "image/jpeg", now),
        )
        assertEquals(
            "Zeta_20261122_090507.gif",
            ImageExport.fileName(RemoteImage("https://example.invalid"), "image/gif", now),
        )
    }

    /** 拿不到字节（还没取到、取不到）或认不出类型时没有可存的东西：查看器的保存、分享置灰。 */
    @Test
    fun nothingToExportWithoutRecognizableBytes() {
        val ref = FileImage("/tmp/a.png")
        assertNull(ImageExport.of(ref, null, now))
        assertNull(ImageExport.of(ref, ByteArray(0), now))
        assertNull(ImageExport.of(ref, ascii("not an image"), now))
        val export = ImageExport.of(ref, png, now)!!
        assertEquals("image/png", export.mime)
        assertEquals("a.png", export.fileName)
    }
}
