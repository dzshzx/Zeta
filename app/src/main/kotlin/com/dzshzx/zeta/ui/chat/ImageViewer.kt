package com.dzshzx.zeta.ui.chat

import android.content.Context
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.dzshzx.zeta.R
import com.dzshzx.zeta.core.ImageExport
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.ImageStore
import com.dzshzx.zeta.core.RemoteImage
import com.dzshzx.zeta.ui.app.findActivity
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PageBarHost
import com.dzshzx.zeta.ui.components.coui2.pageBlurSource
import com.dzshzx.zeta.ui.components.coui2.rememberPageBlur
import io.github.suqi8.coui.kmp.basic.FloatingEditToolbar
import io.github.suqi8.coui.kmp.basic.FloatingEditToolbarDefaults
import io.github.suqi8.coui.kmp.basic.FloatingEditToolbarItem
import io.github.suqi8.coui.kmp.basic.PageIndicator
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.effect.LocalMaterialBackdrop
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Share
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 毛玻璃关着时（截图测试里）顶栏和底栏的底色不透明度：半透明的深色，看得出底下的图。 */
private const val BarFallbackAlpha = 0.85f

/**
 * 全屏看图，照 ColorOS 相册的看图页：黑底铺满整屏，状态栏和导航栏透明、图标浅色。图片层录成毛玻璃的来源，上面盖两条 COUI 的栏： 顶上是 COUI
 * 顶栏（返回钮，多张时标题是第几张），底部是 COUI 的悬浮工具栏（保存到相册、分享），多张时上方是圆点页码；两条栏都取图片层的 COUI 17
 * 底栏材质。点一下图两条栏一起淡出或淡入，打开时露着；双指缩放、双击放大、放大后拖动；左右滑换一张，放大时不翻页。不管外面是浅色还是深色， 里面都用 COUI
 * 的深色配色。进出都淡入淡出，关掉时把停在第几张交给 [onDismiss]。
 */
@Composable
internal fun ImageViewer(refs: List<ImageRef>, start: Int, onDismiss: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { refs.size }
    val zooms = remember { HashMap<Int, ZoomState>() }
    val zoom = zooms.getOrPut(pager.currentPage) { ZoomState() }
    // 翻走的那张回到原大
    LaunchedEffect(pager.settledPage) {
        zooms.forEach { (page, state) -> if (page != pager.settledPage) state.reset() }
    }
    // 各页取到的原始字节（保存、分享用），图显示出来时由那一页交上来
    val originals = remember { mutableStateMapOf<Int, ByteArray>() }
    var chrome by remember { mutableStateOf(true) }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    // 淡出放完再真正关掉
    LaunchedEffect(shown) {
        snapshotFlow { !shown.targetState && shown.isIdle }.first { it }
        dismiss(pager.currentPage)
    }
    val close = { shown.targetState = false }
    val primary = COUITheme.colorScheme.primary
    val dark = remember(primary) { darkColorScheme(primary = primary) }
    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImmersiveWindow()
        COUITheme(colors = dark) {
            val blur = rememberPageBlur()
            AnimatedVisibility(shown, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
                Box(Modifier.fillMaxSize().background(COUITheme.colorScheme.background)) {
                    Box(Modifier.fillMaxSize().pageBlurSource(blur)) {
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            pageSpacing = 16.dp,
                            userScrollEnabled = !zoom.zoomed,
                            key = { page -> refs[page].key },
                        ) { page ->
                            ViewerPage(
                                refs[page],
                                zooms.getOrPut(page) { ZoomState() },
                                onBytes = { originals[page] = it },
                                onTap = { chrome = !chrome },
                            )
                        }
                    }
                    PageBarHost(blur) {
                        val page = pager.currentPage
                        ViewerBars(chrome, pager, refs[page], originals[page], refs.size, close)
                    }
                }
            }
        }
    }
}

/** 顶栏和底栏：一起淡入淡出。 */
@Composable
private fun ViewerBars(
    visible: Boolean,
    pager: PagerState,
    ref: ImageRef,
    bytes: ByteArray?,
    count: Int,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            ViewerTopBar(if (count > 1) "${pager.currentPage + 1} / $count" else "", onBack)
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            ViewerBottomBar(pager, count, ref, bytes)
        }
    }
}

/** 栏的底：有毛玻璃时是 COUI 17 底栏材质（和底部工具栏同一种），关着时是半透明的栏色。 */
@Composable
private fun barBackground(): Modifier =
    LocalMaterialBackdrop.current?.materialBackdrop(
        FloatingEditToolbarDefaults.backdropSpec(COUITheme.colorScheme.isDark),
        RectangleShape,
    ) ?: Modifier.background(COUITheme.colorScheme.bar.copy(alpha = BarFallbackAlpha))

/** 顶栏：透明底的 COUI 顶栏，垫在毛玻璃上，从状态栏下面铺起。 */
@Composable
private fun ViewerTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().then(barBackground())) {
        SmallTopAppBar(
            title = title,
            color = Color.Transparent,
            showDivider = false,
            navigationIcon = {
                IconActionButton(
                    COUIIcons.Back,
                    stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
        )
    }
}

/** 底栏：多张时上面是圆点页码，下面是 COUI 的悬浮工具栏（保存到相册、分享）。这张图的字节还拿不到时两项都置灰。 */
@Composable
private fun ViewerBottomBar(pager: PagerState, count: Int, ref: ImageRef, bytes: ByteArray?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = remember(ref, bytes) { ImageExport.of(ref, bytes, LocalDateTime.now()) }
    val glass = LocalMaterialBackdrop.current != null
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (count > 1) PageIndicator(pagerState = pager, modifier = Modifier.padding(bottom = 4.dp))
        FloatingEditToolbar(
            color =
                FloatingEditToolbarDefaults.color().let {
                    if (glass) it else it.copy(alpha = BarFallbackAlpha)
                },
        ) {
            FloatingEditToolbarItem(
                onClick = { export?.let { scope.saveImage(context, it) } },
                icon = COUIIcons.Download,
                label = "保存到相册",
                enabled = export != null,
            )
            FloatingEditToolbarItem(
                onClick = { export?.let { scope.shareImage(context, it) } },
                icon = COUIIcons.Share,
                label = "分享",
                enabled = export != null,
            )
        }
    }
}

/** 存进相册，成没成都提示一句。 */
private fun CoroutineScope.saveImage(context: Context, image: ImageExport) = launch {
    val saved = withContext(Dispatchers.IO) { image.saveToGallery(context) }
    Toast.makeText(context, if (saved) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
}

/** 写好要分享的文件，打开系统分享面板；没成提示一句。 */
private fun CoroutineScope.shareImage(context: Context, image: ImageExport) = launch {
    val file =
        withContext(Dispatchers.IO) { runCatching { image.writeShareFile(context) }.getOrNull() }
    val shared = file != null && image.share(context.findActivity() ?: context, file)
    if (!shared) Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
}

/** 看图窗口铺进刘海区域，系统栏图标用浅色，不压暗后面，也不放系统的窗口动画（进出由 [ImageViewer] 自己淡入淡出）。 */
@Composable
private fun ImmersiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            window.attributes =
                window.attributes.also {
                    it.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            window.isNavigationBarContrastEnforced = false
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        onDispose {}
    }
}

/** 全屏里的一页：取到了是能缩放的图，并把它的原始字节交给 [onBytes]；没取到时是聊天里同样的说明，网络图没开自动加载时点了加载。 点一下交给 [onTap]。 */
@Composable
private fun ViewerPage(
    ref: ImageRef,
    zoom: ZoomState,
    onBytes: (ByteArray) -> Unit,
    onTap: () -> Unit,
) {
    val waiting = ref is RemoteImage && waitsForTap(ref)
    val loaded = if (waiting) null else rememberChatImage(ref)
    val bitmap = loaded?.bitmap
    if (bitmap != null) {
        val backend = LocalBackend.current
        LaunchedEffect(bitmap, backend) { ImageStore.original(ref, backend)?.let(onBytes) }
        ZoomableImage(bitmap, zoom, onTap)
        return
    }
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            waiting -> RemoteImagePrompt(ref)
            loaded != null -> ImageUnavailable(ref)
        }
    }
}

/** 全屏看图时一张图的缩放倍数和平移。 */
@Stable
internal class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    val zoomed: Boolean
        get() = scale > 1f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/** 全屏看的一张图：双指缩放，放大后拖动，双击在原大和 2.5 倍之间切换，点一下交给 [onTap]。没放大时单指拖动不归它，留给外面左右翻页。 */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, zoom: ZoomState, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val transform = rememberTransformableState { factor, pan, _ ->
        zoom.scale = (zoom.scale * factor).coerceIn(1f, 6f)
        zoom.offset = if (zoom.scale == 1f) Offset.Zero else zoom.offset + pan
    }
    Box(
        modifier =
            Modifier.fillMaxSize()
                .pointerInput(zoom) {
                    detectTapGestures(
                        onTap = { tap() },
                        onDoubleTap = {
                            zoom.scale = if (zoom.zoomed) 1f else 2.5f
                            zoom.offset = Offset.Zero
                        },
                    )
                }
                .transformable(transform, canPan = { zoom.zoomed }),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "图片",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
        )
    }
}
