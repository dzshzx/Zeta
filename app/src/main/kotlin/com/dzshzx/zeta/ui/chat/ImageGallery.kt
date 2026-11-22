package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.RemoteImage
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 图片组里一页最高多高：更高的图等比缩进去。 */
private val GalleryMaxHeight = 360.dp

/** 还不知道比例的一页（在取、取不到、等点了才加载的网络图）多高。 */
private val GalleryPlaceholderHeight = 120.dp

/**
 * 回答里连着的几张图（[answerImages] 分的组）：样子照思考与工具调用的一组（[WorkGroup]），标题行写「N 张图片」，点了收起或展开，
 * 默认展开。展开是横向翻页，一次一张，角上标第几张；点图全屏看，在那里也能左右翻到组里别的图。
 */
@Composable
internal fun ImageGallery(refs: List<ImageRef>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val ink = rememberWorkInk()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(WorkCardShape)
                .border(0.5.dp, ink.border, WorkCardShape)
                .animateContentSize(),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = COUIIcons.Image,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = ink.icon,
            )
            Spacer(Modifier.width(10.dp))
            PulsingText(
                text = "${refs.size} 张图片",
                active = false,
                color = ink.muted,
                modifier = Modifier.weight(1f),
            )
            Chevron(expanded)
        }
        AnimatedVisibility(visible = expanded) {
            GalleryPager(refs, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
        }
    }
}

/** 图片组展开后的横向翻页：高度跟着当前这张的比例（最高 [GalleryMaxHeight]），翻页途中在两张之间过渡。 */
@Composable
private fun GalleryPager(refs: List<ImageRef>, modifier: Modifier) {
    val latest by rememberUpdatedState(refs)
    val pager = rememberPagerState { latest.size }
    // 各张图的宽高比，取到了才有
    val ratios = remember { mutableStateMapOf<String, Float>() }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.fillMaxWidth().galleryHeight(pager) { page ->
            latest.getOrNull(page)?.key?.let(ratios::get)
        },
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 10.dp,
            key = { page -> latest[page].key },
        ) { page ->
            val ref = latest[page]
            GalleryPage(ref, onRatio = { ratios[ref.key] = it }, onOpen = { viewing = page })
        }
        PageCounter(
            "${pager.currentPage + 1} / ${refs.size}",
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
    viewing?.let { start ->
        ImageViewer(refs, start) { last ->
            viewing = null
            // 在全屏里翻到哪张，回来就停在哪张
            scope.launch { pager.scrollToPage(last) }
        }
    }
}

/** 按当前页和翻页的进度定高度：宽度铺满，高度是这一页按比例的高（最高 [GalleryMaxHeight]），翻到一半时取两页之间。 */
private fun Modifier.galleryHeight(pager: PagerState, ratioOf: (Int) -> Float?): Modifier =
    layout { measurable, constraints ->
        val width = constraints.maxWidth
        val heightOf = { page: Int ->
            ratioOf(page)?.let { (width / it).coerceAtMost(GalleryMaxHeight.toPx()) }
                ?: GalleryPlaceholderHeight.toPx()
        }
        val fraction = pager.currentPageOffsetFraction
        val current = pager.currentPage
        val neighbor =
            (if (fraction >= 0) current + 1 else current - 1).coerceIn(
                0,
                (pager.pageCount - 1).coerceAtLeast(0),
            )
        val from = heightOf(current)
        val height = (from + (heightOf(neighbor) - from) * abs(fraction)).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

/** 图片组的一页：图按比例放进这一页、居中，点了全屏看；网络图没开自动加载时是「加载图片」，取不到时是一行说明。 */
@Composable
private fun GalleryPage(ref: ImageRef, onRatio: (Float) -> Unit, onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (ref is RemoteImage && waitsForTap(ref)) {
            RemoteImagePrompt(ref)
        } else {
            LoadedPage(ref, onRatio, onOpen)
        }
    }
}

@Composable
private fun LoadedPage(ref: ImageRef, onRatio: (Float) -> Unit, onOpen: () -> Unit) {
    val loaded = rememberChatImage(ref)
    val bitmap = loaded?.bitmap
    when {
        bitmap != null -> {
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            LaunchedEffect(ratio) { onRatio(ratio) }
            Image(
                bitmap = bitmap,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true)
                        .clip(ChatImageShape)
                        .clickable(onClick = onOpen),
            )
        }
        loaded == null -> ImageLoading(Modifier.fillMaxSize())
        else -> ImageUnavailable(ref)
    }
}

/** 「2 / 6」这样的页码：压在图上的半透明黑底小胶囊。 */
@Composable
private fun PageCounter(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS.copy(fontSize = 11.5.sp),
        color = Color.White,
        modifier =
            modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
