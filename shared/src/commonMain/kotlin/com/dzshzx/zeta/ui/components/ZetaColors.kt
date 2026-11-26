package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 当前配色是不是深色：Miuix 自己也按页面底色的亮度判断（MiuixTheme 里的 isDark）。 */
val Colors.isDark: Boolean
    get() = surface.luminance() < 0.5f

/** 成功色（照 Eta 的 StatusSuccess）：Miuix 的配色里没有这一槽。出错直接用 Miuix 的 `error`。 */
val Colors.success: Color
    get() = SuccessGreen

/**
 * 警告色。Miuix 的配色里没有「警告」这一槽：深色下用 Eta 的琥珀黄，浅色下黄色在白底上字读不清，换成压暗的琥珀色（白底上约 4.9:1）。
 * 文字、图标、圆环、淡底都用这一个，额度、上下文、MCP 状态这类「快不行了」的提示都用它。
 */
val StatusWarning: Color
    @Composable get() = if (MiuixTheme.colorScheme.isDark) WarningOnDark else WarningOnLight

private val SuccessGreen = Color(0xFF34A853)
private val WarningOnDark = Color(0xFFF9AB00)
private val WarningOnLight = Color(0xFFA36200)

// 半透明的中性底：盖在任何底色上都只压暗（深色下提亮）一点，用于输入栏里的小块、代码块、引用条这类浅浅的填充。
// Miuix 的配色里只有实色的容器色，这几档按正文色的透明度给。

/** 最浅的一档（4%）。 */
val Colors.fill4: Color
    get() = onBackground.copy(alpha = 0.04f)

/** 浅的一档（8%），代码块、行内代码的底。 */
val Colors.fill8: Color
    get() = onBackground.copy(alpha = 0.08f)

/** 深的一档（16%），代码块的描边、分隔。 */
val Colors.fill16: Color
    get() = onBackground.copy(alpha = 0.16f)

/** 文字选中、查找命中的高亮：主题色的淡底。 */
val Colors.textHighlight: Color
    get() = primary.copy(alpha = 0.3f)

// 几种强调色：文件类型、提示条、公式等要和主题蓝区分时用。Miuix 的配色里没有这些槽。
val Colors.accentBlue: Color
    get() = Color(0xFF3482FF)

val Colors.accentCyan: Color
    get() = Color(0xFF00A6C8)

val Colors.accentPurple: Color
    get() = Color(0xFF8C5CF5)

val Colors.accentOrange: Color
    get() = Color(0xFFFF8A00)
