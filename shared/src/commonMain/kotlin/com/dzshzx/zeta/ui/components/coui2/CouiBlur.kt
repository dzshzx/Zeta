package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.blur.Backdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.MaterialBlendMode
import io.github.suqi8.coui.kmp.blur.MaterialBlurStyle
import io.github.suqi8.coui.kmp.blur.couiMaterialBlur
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.layerBackdrop
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.effect.LocalPopupMaterialBlur
import io.github.suqi8.coui.kmp.effect.MaterialBlurBlendMode
import io.github.suqi8.coui.kmp.effect.MaterialBlurProvider
import io.github.suqi8.coui.kmp.effect.MaterialBlurSpec
import io.github.suqi8.coui.kmp.theme.COUITheme

// COUI 17 的毛玻璃怎么接到 coui-ui 上：照 COUI 仓库 .claude/skills/coui-app/references/scaffold.md 里的
// CouiBlur.kt
// （Apache-2.0，https://github.com/suqi8/coui）。页面先录进一张背景图，顶栏、对话框、菜单再拿它做模糊。
// 各页面骨架（CouiPageScaffold、聊天页、侧栏容器、浏览器）都经 [PageBlur] 接，不各自拼。

/** 是否用毛玻璃。ZetaTheme 按 windowBackdrop 提供：JVM 截图测试里关掉，那里模糊既慢又会错位。 */
val LocalCouiBlurEnabled = staticCompositionLocalOf { true }

/** 当前页面录下来的背景，由 [PageBlurHost] 提供，页面上弹出的对话框、底部面板、菜单用它做模糊。 */
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 一页的毛玻璃：录下来的页面背景 [backdrop]（顶栏光晕也用它）和交给 coui-ui 弹窗（下拉菜单、列表弹窗、长按菜单）的 [popup]。 毛玻璃关掉时两个都是
 * null。页面骨架这样接：
 *
 * ```
 * val blur = rememberPageBlur()
 * Scaffold(topBar = { 顶栏(blur.backdrop) }) {
 *     Box(Modifier.pageBlurSource(blur)) { PageBlurHost(blur) { 内容 } }
 * }
 * ```
 */
@Immutable class PageBlur(val backdrop: LayerBackdrop?, val popup: MaterialBlurProvider?)

@Composable
fun rememberPageBlur(): PageBlur {
    val backdrop = rememberPageBackdrop()
    val popup = rememberPopupMaterialBlur(backdrop)
    return remember(backdrop, popup) { PageBlur(backdrop, popup) }
}

/** 录制范围：把这一块录进 [blur] 的页面背景。毛玻璃关掉时什么都不做。 */
fun Modifier.pageBlurSource(blur: PageBlur): Modifier =
    blur.backdrop?.let { layerBackdrop(it) } ?: this

/**
 * 里面弹出的对话框、底部面板、菜单拿 [blur] 做毛玻璃。可以在录制范围之外（比如顶栏：它的菜单模糊下面的页面）。 只包在 COUI 的 Scaffold 的槽位里，不包 Scaffold
 * 本身：Scaffold 看外面有没有这份提供来决定弹窗宿主画在哪层。
 */
@Composable
fun PageBlurHost(blur: PageBlur, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPageBackdrop provides blur.backdrop,
        LocalPopupMaterialBlur provides blur.popup,
        content = content,
    )
}

/** 把页面录在窗口底色上的背景图；毛玻璃关掉时为 null。 */
@Composable
private fun rememberPageBackdrop(): LayerBackdrop? {
    if (!LocalCouiBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val background = COUITheme.colorScheme.backgroundWithCard
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

/** 交给 coui-ui 的 LocalPopupMaterialBlur（下拉菜单、列表弹窗、长按菜单）。 */
@Composable
private fun rememberPopupMaterialBlur(backdrop: Backdrop?): MaterialBlurProvider? {
    val active = LocalCouiBlurEnabled.current && backdrop != null && isRuntimeShaderSupported()
    return remember(backdrop, active) {
        if (active && backdrop != null) {
            MaterialBlurProvider { spec, shape ->
                Modifier.couiMaterialBlur(
                    backdrop = backdrop,
                    style = spec.toMaterialBlurStyle(),
                    shape = shape,
                )
            }
        } else {
            null
        }
    }
}

/** 对话框、底部面板的毛玻璃面板：把两个值分别传给组件的 backgroundModifier 和 backgroundColor。 */
@Immutable class PanelBlur(val backgroundModifier: Modifier, val backgroundColor: Color)

/**
 * 当前页面的面板模糊；没有毛玻璃时为 null（保持不透明底色）。
 *
 * @param style 例如 `MaterialBlurDefaults::dialog`、`MaterialBlurDefaults::bottomSheet`。
 */
@Composable
fun panelBlur(style: (isDark: Boolean) -> MaterialBlurStyle): PanelBlur? {
    val backdrop = LocalPageBackdrop.current
    if (!LocalCouiBlurEnabled.current || backdrop == null || !isRuntimeShaderSupported())
        return null
    return PanelBlur(
        backgroundModifier =
            Modifier.couiMaterialBlur(
                backdrop = backdrop,
                style = style(COUITheme.colorScheme.isDark),
            ),
        backgroundColor = Color.Transparent,
    )
}

private fun MaterialBlurSpec.toMaterialBlurStyle(): MaterialBlurStyle =
    MaterialBlurStyle(
        blurRadiusPx = blurRadiusPx,
        mixColor = mixColor,
        blendColor = blendColor,
        blendMode =
            when (blendMode) {
                MaterialBlurBlendMode.Overlay -> MaterialBlendMode.Overlay
                MaterialBlurBlendMode.ColorDodge -> MaterialBlendMode.ColorDodge
                MaterialBlurBlendMode.GlowOverlay -> MaterialBlendMode.GlowOverlay
                MaterialBlurBlendMode.GlowColorDodge -> MaterialBlendMode.GlowColorDodge
            },
    )
