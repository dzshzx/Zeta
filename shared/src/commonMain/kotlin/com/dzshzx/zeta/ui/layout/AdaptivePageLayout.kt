package com.dzshzx.zeta.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 不受界面缩放影响的系统密度：判断宽屏布局时用它，免得缩放一改布局就跳。手机的 ZetaTheme 提供；桌面不缩放，没有它。 */
val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

private val WideScreenMinWidth = 600.dp
private val MaxPageContentWidth = 800.dp

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return isWideScreen(containerSize.width, density)
}

fun isWideScreen(
    containerWidthPx: Int,
    density: androidx.compose.ui.unit.Density,
): Boolean = with(density) { containerWidthPx.toDp() >= WideScreenMinWidth }

/** 列表本身保持全宽，只把内容限制在居中的最大宽度内，避免宽屏两侧形成滚动死区。 */
@Composable
fun WidePageContent(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding =
            if (isWideScreen) {
                ((maxWidth - MaxPageContentWidth) / 2).coerceAtLeast(0.dp)
            } else {
                0.dp
            }
        content(sidePadding)
    }
}

/** 二级页内容只补水平方向的屏幕缺口与手势区；顶部由 TopAppBar、底部由页面末尾留白负责。 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier =
    windowInsetsPadding(
        WindowInsets.displayCutout
            .union(WindowInsets.navigationBars)
            .only(WindowInsetsSides.Horizontal),
    )
