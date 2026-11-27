package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dzshzx.zeta.core.ImageExport
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.ImageStore
import com.dzshzx.zeta.core.RemoteImage
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.LocalInGlassTopBar
import com.dzshzx.zeta.ui.components.miuix.LocalPageBackdrop
import com.dzshzx.zeta.ui.components.miuix.PageBlurHost
import com.dzshzx.zeta.ui.components.miuix.bottomBarGlass
import com.dzshzx.zeta.ui.components.miuix.pageBlurSource
import com.dzshzx.zeta.ui.components.miuix.rememberBottomBarGlass
import com.dzshzx.zeta.ui.components.miuix.rememberPageBackdrop
import java.time.LocalDateTime
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.glass.GlassFloatingToolbar
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

/** 毛玻璃关着时（截图测试里）顶栏和底栏后面渐变压暗最深处的不透明度。 */
private const val BarFallbackAlpha = 0.7f

/**
 * 全屏看图，照 ColorOS 相册的看图页：黑底铺满整屏，状态栏和导航栏透明、图标浅色。图片层录成毛玻璃的来源，上面盖两条栏：顶上是玻璃
 * 顶栏（返回钮，多张时标题是第几张），底部是装着保存到相册、分享两项的玻璃胶囊，多张时上方是圆点页码；两条栏后面都是往图片中间渐渐淡掉的毛玻璃，
 * 和聊天页顶栏一个样子，没有分明的边。点一下图两条栏一起淡出或淡入，打开时露着；双指缩放（能缩到比铺满屏幕小）、双击放大或还原、放大后拖动；左右滑换一张，放大时不翻页。不管外面是浅色还是深色，
 * 里面都用 Miuix 的深色配色。进出都淡入淡出，关掉时把停在第几张交给 [onDismiss]。
 */
@Composable
fun ImageViewer(refs: List<ImageRef>, start: Int, onDismiss: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { refs.size }
    val zooms = remember { HashMap<Int, ZoomState>() }
    // 只在当前这张跨过原大时变：缩放手势每帧改 scale、翻页改 currentPage，都不让整页重组
    val zoomed by remember {
        derivedStateOf { zooms.getOrPut(pager.currentPage) { ZoomState() }.zoomed }
    }
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
    val primary = MiuixTheme.colorScheme.primary
    val dark = remember(primary) { darkColorScheme(primary = primary) }
    Dialog(
        onDismissRequest = close,
        properties = fullScreenDialogProperties(),
    ) {
        ChatPlatform.current.ImmersiveImageWindow()
        MiuixTheme(colors = dark) {
            val pageBackdrop = rememberPageBackdrop()
            AnimatedVisibility(shown, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    Box(
                        Modifier.fillMaxSize().pageBlurSource(pageBackdrop).pointerInput(pager) {
                            pinchGestures { zooms.getOrPut(pager.currentPage) { ZoomState() } }
                        },
                    ) {
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            pageSpacing = 16.dp,
                            userScrollEnabled = !zoomed,
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
                    PageBlurHost(pageBackdrop) {
                        val page = pager.currentPage
                        ViewerBars(chrome, pager, refs[page], originals[page], close)
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
    onBack: () -> Unit,
) {
    val count = pager.pageCount
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
            ViewerBottomBar(pager, ref, bytes)
        }
    }
}

/** 毛玻璃关着时栏后面的渐变压暗：靠屏幕边的一头是 [BarFallbackAlpha] 的底色，往图片中间淡到透明。 */
@Composable
private fun scrim(towardsBottom: Boolean): Brush {
    val edge = MiuixTheme.colorScheme.surface.copy(alpha = BarFallbackAlpha)
    return Brush.verticalGradient(
        if (towardsBottom) listOf(Color.Transparent, edge) else listOf(edge, Color.Transparent),
    )
}

/** 顶栏：和聊天页一样的玻璃小顶栏，后面是往下渐渐淡掉的毛玻璃，没有一条分明的边；毛玻璃关着时是渐变压暗。 */
@Composable
private fun ViewerTopBar(title: String, onBack: () -> Unit) {
    val backdrop = LocalPageBackdrop.current
    Box(
        Modifier.fillMaxWidth()
            .then(if (backdrop == null) Modifier.background(scrim(false)) else Modifier),
    ) {
        GlassTopAppBar(
            title = title,
            // 毛玻璃关着时返回钮也显出底色（Miuix 只在内容滚离顶部时给按钮垫底）
            isContentScrolled = true,
            expandable = false,
            progressiveBlur = backdrop != null,
            backdrop = backdrop,
            bandBrush = SolidColor(Color.Transparent),
            navigationIcon = {
                CompositionLocalProvider(LocalInGlassTopBar provides true) {
                    IconActionButton(
                        MiuixIcons.Glass.ChevronBackward,
                        Strings.chatBack(),
                        onClick = onBack,
                    )
                }
            },
        )
    }
}

/**
 * 底栏：多张时上面是圆点页码，保存到相册、分享两个图标装在一个玻璃胶囊里（毛玻璃关着时胶囊是不透明底色），多张时圆点页码在它上方。后面和顶栏对称：从屏幕底边往上渐渐淡掉的毛玻璃，毛玻璃关着时是渐变压暗。这张图的字节还拿不到时两项都置灰。
 */
@Composable
private fun ViewerBottomBar(pager: PagerState, ref: ImageRef, bytes: ByteArray?) {
    val exporter = ChatPlatform.current.rememberImageExporter()
    val export = remember(ref, bytes) { ImageExport.of(ref, bytes, LocalDateTime.now()) }
    val backdrop = LocalPageBackdrop.current
    val glass = backdrop?.let { rememberBottomBarGlass(it) }
    Box(Modifier.fillMaxWidth()) {
        // 渐变的毛玻璃：导航栏以上 [BottomBandHeight]，再铺到屏幕底边，和顶栏那条一样高（顶栏是状态栏加同样的高度）
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (glass != null) Modifier.bottomBarGlass(glass)
                    else Modifier.background(scrim(true)),
                ),
        ) {
            BarExtent(BottomBandHeight)
        }
        // 胶囊的位置由这一段定：导航栏以上 [BottomBarHeight] 再铺到屏幕底边，胶囊立在它的正中，不跟着渐变的高度走
        BarExtent(BottomBarHeight)
        // 两项装在一个玻璃胶囊里；和顶栏的返回圆钮一套：一样高、一样的材质和描边，透出来的也是压暗后的画面
        GlassFloatingToolbar(
            modifier = Modifier.align(Alignment.Center),
            backdrop = glass?.buttons,
            cornerRadius = BarPillHeight / 2,
            outSidePadding = PaddingValues(0.dp),
            material = GlassTopAppBarDefaults.buttonMaterial(),
            fill = GlassTopAppBarDefaults.buttonFill(),
            stroke = GlassTopAppBarDefaults.buttonStroke(),
        ) {
            Row(
                Modifier.height(BarPillHeight).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction(MiuixIcons.Download, Strings.chatSaveToGallery(), export != null) {
                    export?.let(exporter::save)
                }
                BarAction(MiuixIcons.Share, Strings.chatShare(), export != null) {
                    export?.let(exporter::share)
                }
            }
        }
        if (pager.pageCount > 1) {
            PageDots(
                pager,
                Modifier.align(Alignment.Center).offset(y = -(BarPillHeight / 2 + DotsAboveBar)),
            )
        }
    }
}

/** 导航栏以上 [height]、再铺到屏幕底边的一段空白，用来定高度。 */
@Composable
private fun BarExtent(height: Dp) {
    Column {
        Spacer(Modifier.height(height))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 定胶囊位置的那一段在导航栏以上的高度：胶囊立在这一段（含导航栏）的正中。 */
private val BottomBarHeight = 120.dp

/** 底部渐变毛玻璃在导航栏以上的高度，和玻璃顶栏那条在状态栏以下的高度一样：小顶栏的高度加它往下淡出的那一截。 */
private val BottomBandHeight =
    TopAppBarDefaults.CollapsedHeight + GlassTopAppBarDefaults.BandOverhang

/** 底栏胶囊的高度，比顶栏的返回圆钮（44dp）大一号：里面是两个要常点的操作。 */
private val BarPillHeight = 52.dp

/** 圆点页码的中线在胶囊上边以上多远。 */
private val DotsAboveBar = 15.dp

/** 底栏胶囊里的一项：只有图标，[label] 念给读屏；按下的底色是和胶囊同心的圆，用不了时变淡。 */
@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = MiuixTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        Modifier.size(BarPillHeight - 8.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 视觉居中：两个图标都是下面一个方框、上面伸出一根箭头，笔画的重心比外框的中线低，照外框居中看着往下坠，
        // 所以往上提 1dp，让外框中线和笔画重心分居胶囊中线的上下两边
        Icon(icon, label, Modifier.size(28.dp).offset(y = (-1).dp), tint = tint)
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
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    val zoomed: Boolean
        get() = scale > 1f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 缩放 [factor] 倍、平移 [pan]：倍数夹在 [MinScale]、[MaxScale] 之间，没放大时不平移。 */
    fun transform(factor: Float, pan: Offset) {
        scale = (scale * factor).coerceIn(MinScale, MaxScale)
        offset = if (zoomed) offset + pan else Offset.Zero
    }

    /** 两指松开：离原大不到 [SnapToFit] 就落回原大。 */
    fun settle() {
        if (scale != 1f && abs(scale - 1f) < SnapToFit) reset()
    }
}

/**
 * 两指缩放，放在翻页外面、比翻页先拿到触摸（[PointerEventPass.Initial]）：有两根以上手指按着时由这里缩放 [zoom] 给的那张图，并把移动吃掉，
 * 翻页就不会把捏拢当成左右滑走（不然原大时捏拢缩不了）。一根手指的不管，留给翻页和放大后的拖动。手指全抬起时 [ZoomState.settle]。
 */
private suspend fun PointerInputScope.pinchGestures(zoom: () -> ZoomState) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var pinched = false
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.count { it.pressed } >= 2) {
            pinched = true
            zoom().transform(event.calculateZoom(), event.calculatePan())
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    } while (event.changes.any { it.pressed })
    if (pinched) zoom().settle()
}

/** 双指缩放的范围：最小缩到原大（铺满屏幕的大小）的一半，最大放大 6 倍。 */
private const val MinScale = 0.5f
private const val MaxScale = 6f

/** 松手时离原大不到这么多就落回原大：捏回去时停在 0.98 这样的倍数上，双击、翻页都按没缩放算才顺手。 */
private const val SnapToFit = 0.05f

/**
 * 全屏看的一张图：双指缩放（可以缩到比原大小，见 [MinScale]），放大后拖动，双击回到原大（已经是原大时放大到 2.5 倍），点一下交给 [onTap]。
 * 没放大时单指拖动不归它，留给外面左右翻页。
 */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, zoom: ZoomState, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    // 两指的缩放由外面的 pinchGestures 做；这里剩放大后一根手指的拖动
    val transform = rememberTransformableState { factor, pan, _ -> zoom.transform(factor, pan) }
    Box(
        modifier =
            Modifier.fillMaxSize()
                .pointerInput(zoom) {
                    detectTapGestures(
                        onTap = { tap() },
                        onDoubleTap = {
                            zoom.scale = if (zoom.scale != 1f) 1f else 2.5f
                            zoom.offset = Offset.Zero
                        },
                    )
                }
                .transformable(transform, canPan = { zoom.zoomed }),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = Strings.chatImage(),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize()
                    // 读屏报现在放大了多少，测试也照它核对
                    .semantics { stateDescription = zoomText(zoom.scale) }
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
        )
    }
}

/** 读屏报的缩放：原大，或放大、缩小到几倍（一位小数）。 */
fun zoomText(scale: Float): String =
    when {
        scale == 1f -> Strings.chatZoomActual()
        scale > 1f -> Strings.chatZoomIn("%.1f".format(scale))
        else -> Strings.chatZoomOut("%.1f".format(scale))
    }

/** 全屏看图的对话框：铺满窗口（手机上还铺进系统栏下面）。 */
expect fun fullScreenDialogProperties(): DialogProperties

/** 圆点页码：当前页是白色的实心点，其余是半透明的。 */
@Composable
private fun PageDots(pager: PagerState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(pager.pageCount) { page ->
            Box(
                Modifier.size(6.dp)
                    .clip(CircleShape)
                    .background(
                        Color.White.copy(alpha = if (page == pager.currentPage) 1f else 0.35f),
                    ),
            )
        }
    }
}
