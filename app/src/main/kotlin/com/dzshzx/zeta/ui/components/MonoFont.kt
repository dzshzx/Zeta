package com.dzshzx.zeta.ui.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dzshzx.zeta.R

/**
 * 随包的等宽字体（JetBrains Mono），代码块、命令、路径、日志都用它。不用 [FontFamily.Monospace]： 部分系统会把它换成比例字体。CJK
 * 等缺失字形由系统字体回退。 采用无连字版本，避免 `->`、`!=` 在命令与代码里被合成单个字形。
 */
internal val MonoFont =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
