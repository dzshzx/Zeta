package com.dzshzx.zeta.ui.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.theme.COUIThemeColor
import io.github.suqi8.coui.kmp.theme.COUIThemeColors

internal const val MIN_INTERFACE_SCALE = 0.8f
internal const val MAX_INTERFACE_SCALE = 1.1f

/** 界面缩放比例（[com.dzshzx.zeta.core.Settings.interfaceScale]）收到允许的范围里；读写都经它。 */
internal fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE) else 1f

/**
 * 强调色和它的 COUI 主题色族（[family]）。默认是 COUI 自己的蓝（null）；“系统取色”用壁纸生成的 Monet 色，运行时才知道， 由 [accentThemeColor]
 * 现取；红、橙、绿用 ColorOS 官方色族；紫、粉、青从日间 / 夜间各一档的预设色推出。 预设里没有黄色：COUI 按钮上是白字，黄底白字看不清。
 */
internal enum class AccentChoice(val key: String, val label: String, val family: COUIThemeColor?) {
    Coui("coui", "蓝色（默认）", null),
    System("monet", "系统取色", null),
    Purple("purple", "紫色", COUIThemeColor.fromColor(Color(0xFF7C4DFF), Color(0xFF9475FF))),
    Pink("pink", "粉色", COUIThemeColor.fromColor(Color(0xFFE91E63), Color(0xFFEE4C83))),
    Red("red", "红色", COUIThemeColors.Red),
    Orange("orange", "橙色", COUIThemeColors.Orange),
    Green("green", "绿色", COUIThemeColors.Green),
    Teal("teal", "青色", COUIThemeColor.fromColor(Color(0xFF00A3B5), Color(0xFF14B3C4)));

    companion object {
        fun from(key: String?): AccentChoice = entries.firstOrNull { it.key == key } ?: Coui
    }
}

/** 系统的 Monet 强调色（随壁纸变）。取 COUI 那样偏鲜亮的中间色阶：日间 500，夜间 400。 */
internal fun systemAccent(context: Context, dark: Boolean): Color =
    Color(
        context.getColor(
            if (dark) android.R.color.system_accent1_400 else android.R.color.system_accent1_500,
        ),
    )

/** 强调色对应的 COUI 主题色族；系统取色用 [monet]（[systemAccent] 的结果，已按当前深浅色取好）。null 是 COUI 默认的蓝。 */
internal fun accentThemeColor(accent: AccentChoice, monet: Color?): COUIThemeColor? =
    if (accent == AccentChoice.System) monet?.let { COUIThemeColor.fromColor(it) }
    else accent.family
