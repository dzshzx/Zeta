package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.Attachments
import java.util.Base64
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopFileTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 选了一张大图：附件是缩到长边上限的 JPEG，界面用的小图从它再缩。 */
    @Test
    fun bigImageIsScaledToJpeg() {
        val png =
            Surface.makeRasterN32Premul(3000, 2000).use { surface ->
                surface.makeImageSnapshot().use {
                    checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes
                }
            }
        val file = tmp.newFile("big.png").apply { writeBytes(png) }

        val attachment = checkNotNull(DesktopFile(file).loadImage())

        assertEquals("image/jpeg", attachment.mediaType)
        Image.makeFromEncoded(Base64.getDecoder().decode(attachment.base64)).use {
            assertEquals(Attachments.MAX_EDGE, it.width)
            assertEquals(Attachments.MAX_EDGE * 2 / 3, it.height)
        }
        assertEquals(Attachments.PREVIEW_EDGE, attachment.preview.width)
    }

    @Test
    fun notAnImageGivesNull() {
        val file = tmp.newFile("notes.txt").apply { writeText("不是图片") }
        assertNull(DesktopFile(file).loadImage())
    }
}
