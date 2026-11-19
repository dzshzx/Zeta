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
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode as Coui2SchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController as Coui2ThemeController
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 不受界面缩放影响的系统密度：判断宽屏布局时用它，免得缩放一改布局就跳。 */
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

/**
 * 与 Eta 的 COUI 模式同构：MiuixTheme 只承载文字样式与配色令牌，颜色全部覆盖为 COUI 预设，
 * 界面组件一律用 COUI。
 *
 * 外观设置（强调色、纯黑背景、界面缩放）从 [UiPrefs] 读，改了立即生效。
 * 界面缩放和屏幕常亮只作用于 Activity：悬浮窗（无障碍服务的窗口）保持系统大小，也不碰屏幕常亮。
 *
 * [windowBackdrop] 为 false 时菜单和对话框不采样窗口背景，退回不透明底色：只给 JVM 截图测试用，
 * 那里对话框窗口的屏幕位置不对，透过毛玻璃看到的背景会错位，每帧的模糊又很慢。
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
    val coui = remember(isDark, accent, monet) { couiScheme(isDark, accent, monet) }

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

    val coui2 = remember(isDark, accent, monet) {
        Coui2ThemeController(
            colorSchemeMode = if (isDark) Coui2SchemeMode.Dark else Coui2SchemeMode.Light,
            isDark = isDark,
            themeColor = coui2ThemeColor(accent, monet),
        )
    }
    val controller = remember(isDark) {
        ThemeController(colorSchemeMode = if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light, isDark = isDark)
    }
    CompositionLocalProvider(LocalPlatformDensity provides platformDensity, LocalDensity provides appDensity) {
        MiuixTheme(controller = controller) {
            val base = MiuixTheme.colorScheme
            val colors = remember(base, isDark, coui, pureBlack) {
                val accentContainer = coui.accent.copy(alpha = if (isDark) 0.22f else 0.12f)
                base.copy(
                    primary = coui.accent,
                    onPrimary = coui.onAccent,
                    primaryContainer = accentContainer,
                    onPrimaryContainer = coui.accent,
                    secondaryContainer = if (isDark) Color(0xFF282A2E) else Color(0xFFECEEF1),
                    onSecondaryContainer = coui.onSurface,
                    tertiaryContainer = accentContainer,
                    onTertiaryContainer = coui.accent,
                    background = coui.background,
                    // 纯黑：聊天页等用 surface 铺底的页面也换成纯黑（COUI 深色的页面底色本来就是黑的）
                    surface = if (pureBlack) Color.Black else if (isDark) Color(0xFF121214) else Color.White,
                    surfaceContainer = if (isDark) Color(0xFF1E2024) else Color(0xFFF4F5F7),
                    surfaceContainerHigh = if (isDark) Color(0xFF282A2E) else Color(0xFFEBECEE),
                    surfaceContainerHighest = if (isDark) Color(0xFF32353A) else Color(0xFFE2E4E8),
                    onBackground = coui.onSurface,
                    onSurface = coui.onSurface,
                    onSurfaceContainer = coui.onSurface,
                    onSurfaceVariantSummary = coui.onSurfaceVariant,
                    onSurfaceVariantActions = coui.onSurfaceVariant,
                    outline = if (isDark) Color(0xFF3A3C42) else Color(0xFFDCDFE4),
                    dividerLine = coui.outlineVariant,
                )
            }
            MiuixTheme(colors = colors) {
                CompositionLocalProvider(
                    LocalContentColor provides colors.onBackground,
                    LocalCouiDarkTheme provides isDark,
                    LocalCouiContourGlowEnabled provides true,
                ) {
                    CouiTheme(darkTheme = isDark, colorScheme = coui) {
                        // COUI 2 放在最里层：它提供的点按反馈、越界回弹、选中文字颜色也作用到还没迁移的页面上
                        COUITheme(controller = coui2) {
                            CompositionLocalProvider(LocalCouiBlurEnabled provides windowBackdrop) {
                                // minSdk 33：RuntimeShader 恒可用，菜单与对话框直接采样窗口背景。
                                if (windowBackdrop) {
                                    ProvideCouiMenuBackdrop(isolateContent = false) { windowSource ->
                                        Box(Modifier.fillMaxSize().then(windowSource)) { content() }
                                    }
                                } else {
                                    Box(Modifier.fillMaxSize()) { content() }
                                }
                            }
                        }
                    }
                }
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
