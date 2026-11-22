@file:OptIn(ExperimentalScrollBarApi::class)

package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.ui.layout.WidePageContent
import com.dzshzx.zeta.ui.layout.rememberIsWideScreen
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.TopAppBarDefaults
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchState
import io.github.suqi8.coui.kmp.basic.VerticalScrollBar
import io.github.suqi8.coui.kmp.basic.glowScrollOffsetPx
import io.github.suqi8.coui.kmp.basic.rememberScrollBarAdapter
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.TopAppBarGlow
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.interfaces.ExperimentalScrollBarApi
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

// 页面骨架照 COUI 仓库 .claude/skills/coui-app/references/scaffold.md 里的
// CouiPage.kt（Apache-2.0，https://github.com/suqi8/coui）。
// Zeta 的改动：宽屏判断用 rememberIsWideScreen（不随界面缩放跳），宽屏时内容限在居中的最大宽度内；
// 窗口有焦点时才给键盘让位，免得输入对话框打开时背景页面提前空出键盘高度。

/** 顶栏样式。 */
enum class CouiTopBarStyle {
    /** 大标题，上滑折叠；宽屏时是固定的小顶栏。 */
    Large,

    /** 始终是固定的小顶栏。 */
    Small,

    /** ColorOS 设置首页那种 116dp 高、40sp 标题的顶栏。 */
    SettingsHome,
}

/** 页面内容排版要用的东西：留白（已含顶栏、导航栏和宽屏两侧）、顶栏的滚动联动。 */
@Stable
class CouiPageContentScope(
    val contentPadding: PaddingValues,
    val scrollBehavior: ScrollBehavior,
)

/** 页面列表都要的滚动修饰：滚到头的触感、COUI 越界回弹、和顶栏折叠联动。 */
fun Modifier.couiPageScroll(scope: CouiPageContentScope): Modifier =
    this.scrollEndHaptic()
        .overScrollVertical()
        .nestedScroll(scope.scrollBehavior.nestedScrollConnection)
        .fillMaxHeight()

/** COUI 页面：带 COUI 17 光晕的顶栏；页面录进背景图（[PageBlur]），页面上弹出的对话框、菜单拿它做毛玻璃。 内容随意排，列表页用 [CouiPage]。 */
@Composable
fun CouiPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    topBarStyle: CouiTopBarStyle = CouiTopBarStyle.Large,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable () -> Unit = {},
    topBarSearchState: TopAppBarSearchState? = null,
    content: @Composable (CouiPageContentScope) -> Unit,
) {
    val blur = rememberPageBlur()
    val scrollBehavior = COUIScrollBehavior()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imePadding = if (windowFocused) Modifier.imePadding() else Modifier
    // 键盘弹出时页面已经让到键盘上面，导航栏在键盘后面，底部不再留导航栏的高度
    val imeShown = windowFocused && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        modifier = modifier.fillMaxSize().then(imePadding),
        topBar = {
            CouiPageTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                backdrop = blur.backdrop,
                onBack = onBack,
                style = topBarStyle,
                actions = actions,
                searchState = topBarSearchState,
                bottomContent = topBarBottomContent,
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().pageBlurSource(blur)) {
            WidePageContent { sidePadding ->
                val top = innerPadding.calculateTopPadding() + 12.dp
                val bottom =
                    if (imeShown) 0.dp else max(innerPadding.calculateBottomPadding(), navBottom)
                val start = innerPadding.calculateStartPadding(layoutDirection) + sidePadding
                val end = innerPadding.calculateEndPadding(layoutDirection) + sidePadding
                val scope =
                    remember(top, bottom, start, end, scrollBehavior) {
                        CouiPageContentScope(
                            PaddingValues(start = start, top = top, end = end, bottom = bottom),
                            scrollBehavior,
                        )
                    }
                PageBlurHost(blur) { content(scope) }
            }
        }
    }
}

/**
 * 内容是一个列表的 [CouiPageScaffold]：带页面滚动行为和滚动条，末尾留 32dp。
 *
 * @param dialogs 页面的对话框和底部面板。放在这里、和列表并排，才拿得到页面背景，也不会在所在的列表项滚出屏幕时被销毁。
 */
@Composable
fun CouiPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    topBarStyle: CouiTopBarStyle = CouiTopBarStyle.Large,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    dialogs: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    CouiPageScaffold(
        title = title,
        modifier = modifier,
        onBack = onBack,
        topBarStyle = topBarStyle,
        actions = actions,
        topBarBottomContent = topBarBottomContent,
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.couiPageScroll(scope),
                contentPadding = scope.contentPadding,
            ) {
                content()
                item(key = "coui_page_tail") { Spacer(Modifier.height(32.dp)) }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = scope.contentPadding,
            )
            dialogs()
        }
    }
}

/** 页面顶栏；页面录进了 [backdrop] 时带 COUI 17 的光晕。 */
@Composable
fun CouiPageTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    style: CouiTopBarStyle = CouiTopBarStyle.Large,
    actions: @Composable RowScope.() -> Unit = {},
    searchState: TopAppBarSearchState? = null,
    bottomContent: @Composable () -> Unit = {},
) {
    val colors = COUITheme.colorScheme
    // 透明顶栏加光晕时，像 COUI 17 那样不画分隔线；没有光晕就是实色顶栏
    val barColor = if (backdrop != null) Color.Transparent else colors.backgroundWithCard
    val glow = pageGlow(backdrop, scrollBehavior, colors.isDark, colors.backgroundWithCard)
    val backDescription = "返回"
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) IconActionButton(COUIIcons.Back, backDescription, onClick = onBack)
    }
    val wide = rememberIsWideScreen()
    if (style == CouiTopBarStyle.Small || (wide && style == CouiTopBarStyle.Large)) {
        SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = barColor,
            scrollBehavior = scrollBehavior,
            glow = glow,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    } else {
        LargePageTopBar(
            title = title,
            modifier = modifier,
            barColor = barColor,
            scrollBehavior = scrollBehavior,
            // 设置首页顶栏保留默认的标题变形，折叠后小标题仍停在顶栏里
            home = style == CouiTopBarStyle.SettingsHome,
            glow = glow,
            navigationIcon = navigationIcon,
            actions = actions,
            searchState = searchState,
            bottomContent = bottomContent,
        )
    }
}

/** 页面录进了 [backdrop] 时顶栏上的 COUI 17 光晕，随滚动显出；没有录制时为 null。 */
private fun pageGlow(
    backdrop: LayerBackdrop?,
    scrollBehavior: ScrollBehavior,
    isDark: Boolean,
    baseColor: Color,
): (@Composable BoxScope.() -> Unit)? =
    if (backdrop != null) {
        {
            TopAppBarGlow(
                backdrop = backdrop,
                scrollOffsetPx = scrollBehavior.glowScrollOffsetPx(),
                isDark = isDark,
                baseColor = baseColor,
                enabled = true,
            )
        }
    } else {
        null
    }

/** 大标题顶栏；[home]（设置首页）的大标题更高更大，折叠后小标题仍停在顶栏里。 */
@Composable
private fun LargePageTopBar(
    title: String,
    modifier: Modifier,
    barColor: Color,
    scrollBehavior: ScrollBehavior,
    home: Boolean,
    glow: (@Composable BoxScope.() -> Unit)?,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    searchState: TopAppBarSearchState?,
    bottomContent: @Composable () -> Unit,
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        color = barColor,
        scrollBehavior = scrollBehavior,
        expandedHeight = if (home) 116.dp else TopAppBarDefaults.ExpandedHeight,
        largeTitleFontSize = if (home) 40.sp else TopAppBarDefaults.largeTitleFontSize(),
        titlePadding = if (home) 24.dp else Dp.Unspecified,
        largeTitleBottomMargin = if (home) 18.dp else Dp.Unspecified,
        glow = glow,
        navigationIcon = navigationIcon,
        actions = actions,
        searchState = searchState,
        bottomContent = bottomContent,
    )
}
