package com.dzshzx.zeta.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** 手机上的 [ImageCodec]：[Images.decodeWithin]（ImageDecoder，解码时就缩小）。登记在 app 的 META-INF/services 里。 */
class AndroidImageCodec : ImageCodec {
    override fun decodeWithin(bytes: ByteArray, edge: Int): ImageBitmap =
        Images.decodeWithin(bytes, edge).bitmap.asImageBitmap()
}
