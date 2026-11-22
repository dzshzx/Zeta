package com.dzshzx.zeta.ui.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dzshzx.zeta.R

/** 手机上的 [Fonts]：res/font 里的字体。登记在 app 的 META-INF/services 里。 */
class AndroidFonts : Fonts {
    override val mono: FontFamily =
        FontFamily(
            Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
            Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
        )
}
