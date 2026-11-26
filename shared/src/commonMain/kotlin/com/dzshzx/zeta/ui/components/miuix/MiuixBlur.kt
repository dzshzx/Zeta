package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面的毛玻璃（HyperOS 4 的玻璃材质，做法照 Eta 的 GlassBackdrop.kt）：页面先录进一张背景图，
// 玻璃顶栏、玻璃对话框、底部面板、菜单再拿它做模糊。各页面骨架（MiuixPageScaffold、聊天页、侧栏容器、浏览器）都经 [PageBlur] 接，不各自拼。

/** 是否用毛玻璃。ZetaTheme 按 windowBackdrop 提供：JVM 截图测试里关掉，那里模糊既慢又会错位。 */
val LocalMiuixBlurEnabled = staticCompositionLocalOf { true }

/** 当前页面录下来的背景，由 [PageBlurHost] 提供，页面上弹出的玻璃对话框、底部面板、菜单用它做模糊；毛玻璃关掉时为 null。 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 一页的毛玻璃：录下来的页面背景 [backdrop]，玻璃顶栏和页面上弹出的东西都用它。毛玻璃关掉时为 null。页面骨架这样接：
 *
 * ```
 * val blur = rememberPageBlur()
 * Scaffold(topBar = { 顶栏(blur.backdrop) }) {
 *     Box(Modifier.pageBlurSource(blur)) { PageBlurHost(blur) { 内容 } }
 * }
 * ```
 */
@Immutable class PageBlur(val backdrop: LayerBackdrop?)

@Composable
fun rememberPageBlur(): PageBlur {
    val backdrop = rememberPageBackdrop()
    return remember(backdrop) { PageBlur(backdrop) }
}

/** 录制范围：把这一块录进 [blur] 的页面背景。毛玻璃关掉时什么都不做。 */
fun Modifier.pageBlurSource(blur: PageBlur): Modifier =
    blur.backdrop?.let { layerBackdrop(it) } ?: this

/** 里面弹出的对话框、底部面板、菜单拿 [blur] 做毛玻璃。可以在录制范围之外（比如顶栏：它的菜单模糊下面的页面）。 */
@Composable
fun PageBlurHost(blur: PageBlur, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageBackdrop provides blur.backdrop, content = content)
}

/**
 * 从屏幕底边往上渐渐淡掉的毛玻璃，和玻璃顶栏的渐进模糊对称，没有分明的边。铺在录制范围之外的栏后面（铺在里面会模糊到自己）；[backdrop] 取
 * [LocalPageBackdrop]，毛玻璃关掉时那里是 null，由调用方画替代。
 */
@Composable
fun Modifier.bottomBarGlass(backdrop: Backdrop): Modifier =
    progressiveTextureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        gradient = ProgressiveBlur.Bottom,
        blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
        colors = BlurDefaults.blurColors(),
    )

/** 把页面录在窗口底色上的背景图；毛玻璃关掉或系统不支持运行时着色器时为 null。 */
@Composable
private fun rememberPageBackdrop(): LayerBackdrop? {
    if (!LocalMiuixBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val background = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}
