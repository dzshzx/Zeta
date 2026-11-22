package com.dzshzx.zeta.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** 桌面上的 [Fonts]：桌面版构建时从 app 的 res/font 拷进 jar 的 font/ 下。登记在 shared 的 desktopMain 资源里。 */
class DesktopFonts : Fonts {
    override val mono: FontFamily =
        FontFamily(
            Font("font/jetbrains_mono_regular.ttf", FontWeight.Normal),
            Font("font/jetbrains_mono_bold.ttf", FontWeight.Bold),
        )
}
