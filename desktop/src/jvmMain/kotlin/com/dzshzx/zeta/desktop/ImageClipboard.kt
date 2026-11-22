package com.dzshzx.zeta.desktop

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import com.dzshzx.zeta.core.SkiaImageCodec
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/** 把一张图放进系统剪贴板（桌面上的「分享」）。 */
object ImageClipboard {
    /** [bytes] 是图片文件的字节，和看图时一样经 Skia 解码；解不开时抛出。耗时，别在界面线程上调用。 */
    fun copy(bytes: ByteArray) {
        val image = SkiaImageCodec.decode(bytes, Int.MAX_VALUE).asComposeImageBitmap().toAwtImage()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(Selection(image), null)
    }

    private class Selection(private val image: Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) image
            else throw UnsupportedFlavorException(flavor)
    }
}
