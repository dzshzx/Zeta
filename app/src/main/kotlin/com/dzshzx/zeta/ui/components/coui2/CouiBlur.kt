package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.blur.Backdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.MaterialBlendMode
import io.github.suqi8.coui.kmp.blur.MaterialBlurStyle
import io.github.suqi8.coui.kmp.blur.ViewBlurStyle
import io.github.suqi8.coui.kmp.blur.couiMaterialBlur
import io.github.suqi8.coui.kmp.blur.couiViewBlur
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.effect.MaterialBackdropProvider
import io.github.suqi8.coui.kmp.effect.MaterialBackdropSpec
import io.github.suqi8.coui.kmp.effect.MaterialBlurBlendMode
import io.github.suqi8.coui.kmp.effect.MaterialBlurProvider
import io.github.suqi8.coui.kmp.effect.MaterialBlurSpec
import io.github.suqi8.coui.kmp.theme.COUITheme

// COUI 17 的毛玻璃怎么接到 coui-ui 上：照 COUI 仓库 .claude/skills/coui-app/references/scaffold.md 里的 CouiBlur.kt
// （Apache-2.0，https://github.com/suqi8/coui）。页面先录进一张背景图，顶栏、对话框、菜单、悬浮按钮再拿它做模糊。

/** 是否用毛玻璃。ZetaTheme 按 windowBackdrop 提供：JVM 截图测试里关掉，那里模糊既慢又会错位。 */
internal val LocalCouiBlurEnabled = staticCompositionLocalOf { true }

/** 当前页面录下来的背景，由 [CouiPageScaffold] 提供，页面上弹出的对话框、底部面板、菜单用它做模糊。 */
internal val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 把页面录在窗口底色上的背景图；毛玻璃关掉时为 null。 */
@Composable
internal fun rememberPageBackdrop(): LayerBackdrop? {
    if (!LocalCouiBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val background = COUITheme.colorScheme.backgroundWithCard
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

/** 交给 coui-ui 的 LocalMaterialBackdrop（按钮、悬浮栏、悬浮按钮、工具栏）。背景图里只能有它们后面的东西，不能有它们自己。 */
@Composable
internal fun rememberMaterialBackdropProvider(backdrop: Backdrop?): MaterialBackdropProvider? {
    val active = LocalCouiBlurEnabled.current && backdrop != null && isRuntimeShaderSupported()
    return remember(backdrop, active) {
        if (active && backdrop != null) {
            MaterialBackdropProvider { spec, shape ->
                Modifier.couiViewBlur(backdrop = backdrop, style = spec.toViewBlurStyle(), shape = shape)
            }
        } else {
            null
        }
    }
}

/** 交给 coui-ui 的 LocalPopupMaterialBlur（下拉菜单、列表弹窗、长按菜单）。 */
@Composable
internal fun rememberPopupMaterialBlur(backdrop: Backdrop?): MaterialBlurProvider? {
    val active = LocalCouiBlurEnabled.current && backdrop != null && isRuntimeShaderSupported()
    return remember(backdrop, active) {
        if (active && backdrop != null) {
            MaterialBlurProvider { spec, shape ->
                Modifier.couiMaterialBlur(backdrop = backdrop, style = spec.toMaterialBlurStyle(), shape = shape)
            }
        } else {
            null
        }
    }
}

/** 对话框、底部面板的毛玻璃面板：把两个值分别传给组件的 backgroundModifier 和 backgroundColor。 */
@Immutable
internal class PanelBlur(val backgroundModifier: Modifier, val backgroundColor: Color)

/**
 * 当前页面的面板模糊；没有毛玻璃时为 null（保持不透明底色）。
 *
 * @param style 例如 `MaterialBlurDefaults::dialog`、`MaterialBlurDefaults::bottomSheet`。
 */
@Composable
internal fun panelBlur(style: (isDark: Boolean) -> MaterialBlurStyle): PanelBlur? {
    val backdrop = LocalPageBackdrop.current
    if (!LocalCouiBlurEnabled.current || backdrop == null || !isRuntimeShaderSupported()) return null
    return PanelBlur(
        backgroundModifier = Modifier.couiMaterialBlur(backdrop = backdrop, style = style(COUITheme.colorScheme.isDark)),
        backgroundColor = Color.Transparent,
    )
}

private fun MaterialBackdropSpec.toViewBlurStyle(): ViewBlurStyle = ViewBlurStyle(
    blurRadiusPx = blurRadiusPx,
    layer1 = layer1,
    mode1 = mode1,
    layer2 = layer2,
    mode2 = mode2,
)

private fun MaterialBlurSpec.toMaterialBlurStyle(): MaterialBlurStyle = MaterialBlurStyle(
    blurRadiusPx = blurRadiusPx,
    mixColor = mixColor,
    blendColor = blendColor,
    blendMode = when (blendMode) {
        MaterialBlurBlendMode.Overlay -> MaterialBlendMode.Overlay
        MaterialBlurBlendMode.ColorDodge -> MaterialBlendMode.ColorDodge
        MaterialBlurBlendMode.GlowOverlay -> MaterialBlendMode.GlowOverlay
        MaterialBlurBlendMode.GlowColorDodge -> MaterialBlendMode.GlowColorDodge
    },
)
