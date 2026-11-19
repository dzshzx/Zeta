package com.dzshzx.zeta.ui.app

import android.util.DisplayMetrics
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.dzshzx.zeta.ui.components.coui2.LocalCouiBlurEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.theme.darkColorScheme

/** 不受界面缩放影响的系统密度：判断宽屏布局时用它，免得缩放一改布局就跳。 */
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Zeta 的主题：COUI 2（ColorOS 17）的配色和文字样式。
 *
 * 外观设置（强调色、纯黑背景、界面缩放）从 [UiPrefs] 读，改了立即生效。强调色映射到 COUI 的主题色族（[accentThemeColor]）；
 * 纯黑背景让深色下侧栏这类抬高的底面也变成纯黑（COUI 深色的页面底色本来就是纯黑），对话框、菜单不变，免得和背景分不开。
 * 界面缩放和屏幕常亮只作用于 Activity：悬浮窗（无障碍服务的窗口）保持系统大小，也不碰屏幕常亮。
 *
 * [windowBackdrop] 为 false 时关掉毛玻璃，弹出的东西用不透明底色：只给 JVM 截图测试用，那里模糊既慢又会错位。
 */
@Composable
fun ZetaTheme(darkTheme: Boolean? = null, windowBackdrop: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val prefs = remember(context) { UiPrefs.get(context) }
    val activity = remember(context) { context.findActivity() }
    val isDark = darkTheme ?: isSystemInDarkTheme()
    val accent = prefs.accent
    val pureBlack = prefs.pureBlack && isDark
    // 壁纸换了，系统会推一次配置变化，这里跟着重新取 Monet 色
    val monet = remember(context, configuration, isDark, accent) {
        if (accent == AccentChoice.System) runCatching { systemAccent(context, isDark) }.getOrNull() else null
    }

    // —— 界面缩放（照 Eta）：以 densityDpi / 160 为 100% 基准，乘上缩放比例 ——
    val initialDensity = remember(context) { context.resources.displayMetrics.density }
    val baseDensity = if (configuration.densityDpi > 0) configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT else initialDensity
    val scaled = activity != null
    val targetDensity = baseDensity * (if (scaled) prefs.interfaceScale else 1f)
    val platformDensity = remember(context, baseDensity, configuration.fontScale) {
        val metrics = context.resources.displayMetrics
        val saved = metrics.density
        metrics.density = baseDensity
        Density(context).also { metrics.density = saved }
    }
    if (scaled) {
        // 对话框、弹出菜单是另起的窗口，新窗口从宿主 Context 的 resources 取密度；同步写进去，两边缩放才一致
        applyDensity(context, targetDensity, configuration.fontScale)
    }
    // 离开时恢复成系统密度；只跟着 context 走，免得键变化时旧的 onDispose 把刚写进去的新值又改回去
    val restore by rememberUpdatedState(baseDensity to configuration.fontScale)
    DisposableEffect(context, scaled) {
        onDispose { if (scaled) applyDensity(context, restore.first, restore.second) }
    }
    // 悬浮窗也显式给出系统密度：即使 resources 的 DisplayMetrics 与 Activity 共用、被上面改过，悬浮窗也不跟着缩放
    val appDensity = remember(context, targetDensity, configuration.fontScale, platformDensity) {
        if (scaled) Density(context) else platformDensity
    }

    val controller = remember(isDark, accent, monet, pureBlack) {
        ThemeController(
            colorSchemeMode = if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            // 纯黑背景：侧栏的抬高底色（只有侧栏用它）也换成纯黑
            darkColors = darkColorScheme().let { if (pureBlack) it.copy(backgroundElevatedWithCard = Color.Black) else it },
            themeColor = accentThemeColor(accent, monet),
        )
    }
    CompositionLocalProvider(LocalPlatformDensity provides platformDensity, LocalDensity provides appDensity) {
        COUITheme(controller = controller) {
            CompositionLocalProvider(LocalCouiBlurEnabled provides windowBackdrop) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    if (activity != null) KeepScreenOnWhileRunning(activity, prefs)
}

@Suppress("DEPRECATION")
private fun applyDensity(context: android.content.Context, density: Float, fontScale: Float) {
    val metrics = context.resources.displayMetrics
    metrics.density = density
    metrics.scaledDensity = density * fontScale
}
