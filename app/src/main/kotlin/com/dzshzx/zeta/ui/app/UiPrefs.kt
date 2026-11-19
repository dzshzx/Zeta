package com.dzshzx.zeta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.dzshzx.zeta.core.Settings
import io.github.suqi8.coui.kmp.theme.COUIThemeColor
import io.github.suqi8.coui.kmp.theme.COUIThemeColors

internal const val MIN_INTERFACE_SCALE = 0.8f
internal const val MAX_INTERFACE_SCALE = 1.1f

internal fun normalizeInterfaceScale(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE) else 1f

/**
 * 强调色和它的 COUI 主题色族（[family]）。默认是 COUI 自己的蓝（null）；“系统取色”用壁纸生成的 Monet 色，运行时才知道，
 * 由 [accentThemeColor] 现取；红、橙、绿用 ColorOS 官方色族；紫、粉、青从日间 / 夜间各一档的预设色推出。
 * 预设里没有黄色：COUI 按钮上是白字，黄底白字看不清。
 */
internal enum class AccentChoice(val key: String, val label: String, val family: COUIThemeColor?) {
    Coui("coui", "蓝色（默认）", null),
    System("monet", "系统取色", null),
    Purple("purple", "紫色", COUIThemeColor.fromColor(Color(0xFF7C4DFF), Color(0xFF9475FF))),
    Pink("pink", "粉色", COUIThemeColor.fromColor(Color(0xFFE91E63), Color(0xFFEE4C83))),
    Red("red", "红色", COUIThemeColors.Red),
    Orange("orange", "橙色", COUIThemeColors.Orange),
    Green("green", "绿色", COUIThemeColors.Green),
    Teal("teal", "青色", COUIThemeColor.fromColor(Color(0xFF00A3B5), Color(0xFF14B3C4))),
    ;

    companion object {
        fun from(key: String?): AccentChoice = entries.firstOrNull { it.key == key } ?: Coui
    }
}

/**
 * 界面相关的设置（外观、屏幕常亮）的可观察副本。主界面和悬浮窗都读它，改了马上生效。
 * ConversationHub 里只放了深浅色，这几项单独放在这里，存储仍走 [Settings]。
 */
internal class UiPrefs private constructor(private val settings: Settings) {
    var accent by mutableStateOf(AccentChoice.from(settings.accent))
        private set
    var interfaceScale by mutableFloatStateOf(normalizeInterfaceScale(settings.interfaceScale))
        private set
    var keepScreenOn by mutableStateOf(settings.keepScreenOn)
        private set

    fun chooseAccent(value: AccentChoice) {
        accent = value
        settings.accent = value.key
    }

    fun chooseInterfaceScale(value: Float) {
        val scale = normalizeInterfaceScale(value)
        interfaceScale = scale
        settings.interfaceScale = scale
    }

    fun chooseKeepScreenOn(enabled: Boolean) {
        keepScreenOn = enabled
        settings.keepScreenOn = enabled
    }

    /** 设置从备份导入后按存下来的值重读。 */
    fun reload() {
        accent = AccentChoice.from(settings.accent)
        interfaceScale = normalizeInterfaceScale(settings.interfaceScale)
        keepScreenOn = settings.keepScreenOn
    }

    companion object {
        @Volatile private var instance: UiPrefs? = null

        fun get(context: Context): UiPrefs = instance ?: synchronized(this) {
            instance ?: UiPrefs(Settings(context.applicationContext)).also { instance = it }
        }
    }
}

/** 系统的 Monet 强调色（随壁纸变）。取 COUI 那样偏鲜亮的中间色阶：日间 500，夜间 400。 */
internal fun systemAccent(context: Context, dark: Boolean): Color =
    Color(context.getColor(if (dark) android.R.color.system_accent1_400 else android.R.color.system_accent1_500))

/** 强调色对应的 COUI 主题色族；系统取色用 [monet]（[systemAccent] 的结果，已按当前深浅色取好）。null 是 COUI 默认的蓝。 */
internal fun accentThemeColor(accent: AccentChoice, monet: Color?): COUIThemeColor? =
    if (accent == AccentChoice.System) monet?.let { COUIThemeColor.fromColor(it) } else accent.family
