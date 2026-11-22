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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.of
import com.dzshzx.zeta.ui.components.coui2.LocalCouiBlurEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

/** 不受界面缩放影响的系统密度：判断宽屏布局时用它，免得缩放一改布局就跳。 */
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

/**
 * Zeta 的主题：COUI 2（ColorOS 17）的配色和文字样式。
 *
 * 主题色用 COUI 默认的蓝。界面缩放从 [Settings] 读，改了立即生效。 COUI 深色的页面底色本来就是纯黑，Zeta 不另设纯黑开关。 界面缩放和屏幕常亮只作用于
 * Activity：悬浮窗（无障碍服务的窗口）保持系统大小，也不碰屏幕常亮。
 *
 * [windowBackdrop] 为 false 时关掉毛玻璃，弹出的东西用不透明底色：只给 JVM 截图测试用，那里模糊既慢又会错位。
 */
@Composable
fun ZetaTheme(
    darkTheme: Boolean? = null,
    windowBackdrop: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val settings = remember(context) { Settings.of(context) }
    val activity = remember(context) { context.findActivity() }
    val isDark = darkTheme ?: isSystemInDarkTheme()

    // —— 界面缩放（照 Eta）：以 densityDpi / 160 为 100% 基准，乘上缩放比例 ——
    val initialDensity = remember(context) { context.resources.displayMetrics.density }
    val baseDensity = baseDensity(configuration.densityDpi, initialDensity)
    val scaled = activity != null
    val targetDensity =
        baseDensity * (if (scaled) normalizeInterfaceScale(settings.interfaceScale) else 1f)
    val platformDensity =
        remember(context, baseDensity, configuration.fontScale) {
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
    val appDensity =
        remember(context, targetDensity, configuration.fontScale, platformDensity) {
            if (scaled) Density(context) else platformDensity
        }

    val controller =
        remember(isDark) {
            ThemeController(
                colorSchemeMode = if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            )
        }
    CompositionLocalProvider(
        LocalPlatformDensity provides platformDensity,
        LocalDensity provides appDensity,
    ) {
        COUITheme(controller = controller) {
            CompositionLocalProvider(LocalCouiBlurEnabled provides windowBackdrop) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    if (activity != null) KeepScreenOnWhileRunning(activity, settings)
}

/** 100% 缩放的基准密度：densityDpi / 160，配置里没有 densityDpi 时用初始密度。 */
private fun baseDensity(densityDpi: Int, initialDensity: Float) =
    if (densityDpi > 0) densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT else initialDensity

@Suppress("DEPRECATION")
private fun applyDensity(context: android.content.Context, density: Float, fontScale: Float) {
    val metrics = context.resources.displayMetrics
    metrics.density = density
    metrics.scaledDensity = density * fontScale
}
