package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
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
// 玻璃顶栏、玻璃对话框、底部面板、菜单再拿它做模糊。各页面骨架（MiuixPageScaffold、聊天页、侧栏容器、浏览器）都经 [rememberPageBackdrop] 接，不各自拼。

/** 是否用毛玻璃。ZetaTheme 按 windowBackdrop 提供：JVM 截图测试里关掉，那里模糊既慢又会错位。 */
val LocalMiuixBlurEnabled = staticCompositionLocalOf { true }

/** 当前页面录下来的背景，由 [PageBlurHost] 提供，页面上弹出的玻璃对话框、底部面板、菜单用它做模糊；毛玻璃关掉时为 null。 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 录制范围：把这一块录进页面背景 [backdrop]（[rememberPageBackdrop]）。毛玻璃关掉时什么都不做。 */
fun Modifier.pageBlurSource(backdrop: LayerBackdrop?): Modifier =
    backdrop?.let { layerBackdrop(it) } ?: this

/** 里面弹出的对话框、底部面板、菜单拿 [backdrop] 做毛玻璃。可以在录制范围之外（比如顶栏：它的菜单模糊下面的页面）。 */
@Composable
fun PageBlurHost(backdrop: Backdrop?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageBackdrop provides backdrop, content = content)
}

/**
 * 底栏的毛玻璃，[rememberBottomBarGlass] 建：[page] 是页面的背景图，[buttons] 是连这条栏一起录下的画面，栏上的玻璃按钮拿它取样
 * （和玻璃顶栏里的按钮一样，透出来的是压暗、模糊之后的画面，不是原图）。
 */
@Stable
class BottomBarGlass internal constructor(internal val page: Backdrop) {
    internal var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    lateinit var buttons: LayerBackdrop
        internal set
}

/** 建一条底栏的毛玻璃；[page] 取 [LocalPageBackdrop]，毛玻璃关掉时那里是 null，由调用方画替代。 */
@Composable
fun rememberBottomBarGlass(page: Backdrop): BottomBarGlass {
    val glass = remember(page) { BottomBarGlass(page) }
    glass.buttons = rememberLayerBackdrop {
        with(glass.page) {
            drawBackdrop(this@rememberLayerBackdrop, glass.coordinates?.takeIf { it.isAttached })
        }
        drawContent()
    }
    return glass
}

/**
 * 从屏幕底边往上渐渐淡掉的毛玻璃，没有分明的边，是玻璃顶栏那条渐进模糊倒过来：同样的模糊半径和曲线、同样掺两成底色、同样一层往中间淡掉的 压暗（顶栏的数值见 Miuix 的
 * GlassTopAppBar）。铺在录制范围之外的栏后面（铺在里面会模糊到自己）。
 */
@Composable
fun Modifier.bottomBarGlass(glass: BottomBarGlass): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    val blend = remember(surface) { listOf(BlendColorEntry(surface.copy(alpha = 0.2f))) }
    val scrim =
        remember(surface) {
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.1f to surface.copy(alpha = 0.05f),
                0.25f to surface.copy(alpha = 0.16f),
                0.5f to surface.copy(alpha = 0.32f),
                0.75f to surface.copy(alpha = 0.44f),
                1f to surface.copy(alpha = 0.50f),
            )
        }
    return onGloballyPositioned { glass.coordinates = it }
        .layerBackdrop(glass.buttons)
        .progressiveTextureBlur(
            backdrop = glass.page,
            shape = RectangleShape,
            gradient = BottomBarGradient,
            blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
            colors = BlurDefaults.blurColors(blendColors = blend),
        )
        .drawWithContent {
            drawContent()
            drawRect(scrim)
        }
}

/** 和玻璃顶栏同一条曲线，方向朝上。 */
private val BottomBarGradient = ProgressiveBlur.Bottom.copy(curve = 0.25f)

/**
 * 一页的毛玻璃：把页面录在窗口底色上的背景图，玻璃顶栏和页面上弹出的东西都用它；毛玻璃关掉或系统不支持运行时着色器时为 null。页面骨架这样接：
 *
 * ```
 * val pageBackdrop = rememberPageBackdrop()
 * Scaffold(topBar = { 顶栏(pageBackdrop) }) {
 *     Box(Modifier.pageBlurSource(pageBackdrop)) { PageBlurHost(pageBackdrop) { 内容 } }
 * }
 * ```
 */
@Composable
fun rememberPageBackdrop(): LayerBackdrop? {
    if (!LocalMiuixBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val background = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}
