package io.github.dzshzx.zeta.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiPresets
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

/**
 * 与 Eta 的 COUI 模式同构：MiuixTheme 只承载文字样式与配色令牌，颜色全部覆盖为 COUI 预设，
 * 界面组件一律用 COUI。
 */
@Composable
fun ZetaTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = darkTheme ?: isSystemInDarkTheme()
    val controller = remember(isDark) {
        ThemeController(colorSchemeMode = if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light, isDark = isDark)
    }
    MiuixTheme(controller = controller) {
        val base = MiuixTheme.colorScheme
        val coui = if (isDark) CouiPresets.Dark else CouiPresets.Light
        val colors = remember(base, isDark) {
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
                surface = if (isDark) Color(0xFF121214) else Color.White,
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
                    // minSdk 33：RuntimeShader 恒可用，菜单与对话框直接采样窗口背景。
                    ProvideCouiMenuBackdrop(isolateContent = false) { windowSource ->
                        Box(Modifier.fillMaxSize().then(windowSource)) { content() }
                    }
                }
            }
        }
    }
}
