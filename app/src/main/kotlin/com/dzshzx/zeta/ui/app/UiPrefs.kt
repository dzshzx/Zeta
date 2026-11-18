package com.dzshzx.zeta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.dzshzx.zeta.core.Settings
import com.itos.coui.theme.CouiColor
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiPresets

internal const val MIN_INTERFACE_SCALE = 0.8f
internal const val MAX_INTERFACE_SCALE = 1.1f

internal fun normalizeInterfaceScale(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE) else 1f

/**
 * 强调色。默认是 COUI 自己的蓝；“系统取色”用壁纸生成的 Monet 色；其余是固定的预设色（日间 / 夜间各一档）。
 * 预设里没有黄色：COUI 按钮上是白字，黄底白字看不清。
 */
internal enum class AccentChoice(val key: String, val label: String, val day: Color?, val night: Color?) {
    Coui("coui", "蓝色（默认）", null, null),
    System("monet", "系统取色", null, null),
    Purple("purple", "紫色", Color(0xFF7C4DFF), Color(0xFF9475FF)),
    Pink("pink", "粉色", Color(0xFFE91E63), Color(0xFFEE4C83)),
    Red("red", "红色", CouiColor.Day.RED, CouiColor.Night.RED),
    Orange("orange", "橙色", CouiColor.Day.ORANGE, CouiColor.Night.ORANGE),
    Green("green", "绿色", CouiColor.Day.GREEN, CouiColor.Night.GREEN),
    Teal("teal", "青色", Color(0xFF00A3B5), Color(0xFF14B3C4)),
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
    var pureBlack by mutableStateOf(settings.pureBlack)
        private set
    var interfaceScale by mutableFloatStateOf(normalizeInterfaceScale(settings.interfaceScale))
        private set
    var keepScreenOn by mutableStateOf(settings.keepScreenOn)
        private set

    fun chooseAccent(value: AccentChoice) {
        accent = value
        settings.accent = value.key
    }

    fun choosePureBlack(enabled: Boolean) {
        pureBlack = enabled
        settings.pureBlack = enabled
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

    companion object {
        @Volatile private var instance: UiPrefs? = null

        fun get(context: Context): UiPrefs = instance ?: synchronized(this) {
            instance ?: UiPrefs(Settings(context.applicationContext)).also { instance = it }
        }
    }
}

/**
 * 系统的 Monet 强调色（随壁纸变）。取 COUI 那样偏鲜亮的中间色阶：日间 500，夜间 400；
 * 夜间写在深色表面上的文字再亮一档（300），对应 COUI 预设里的 accentOnSurface。
 */
internal fun systemAccent(context: Context, dark: Boolean): Pair<Color, Color> {
    fun color(id: Int) = Color(context.getColor(id))
    return if (dark) {
        color(android.R.color.system_accent1_400) to color(android.R.color.system_accent1_300)
    } else {
        color(android.R.color.system_accent1_500).let { it to it }
    }
}

/** 在 COUI 预设上换掉强调色；[monet] 是 [systemAccent] 的结果，只在选了系统取色时用。 */
internal fun couiScheme(dark: Boolean, accent: AccentChoice, monet: Pair<Color, Color>?): CouiColorScheme {
    val base = if (dark) CouiPresets.Dark else CouiPresets.Light
    val (fill, onSurface) = when {
        accent == AccentChoice.System && monet != null -> monet
        accent.day != null && accent.night != null -> {
            val c = if (dark) accent.night else accent.day
            // 夜间文字用的那档提亮一些，和 COUI 蓝在深色下的做法一样
            c to if (dark) lerp(c, Color.White, 0.25f) else c
        }
        else -> return base
    }
    return base.copy(accent = fill, accentOnSurface = onSurface, checkboxAccent = fill, menuCheckboxAccent = onSurface)
}
