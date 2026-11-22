package com.dzshzx.zeta.desktop

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import javax.imageio.ImageIO

/** 把一张图放进系统剪贴板（桌面上的「分享」）。 */
object ImageClipboard {
    /** [bytes] 是图片文件的字节；ImageIO 解不开（比如 WebP）时抛出。 */
    fun copy(bytes: ByteArray) {
        val image = checkNotNull(ImageIO.read(bytes.inputStream())) { "认不出这种图片" }
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
