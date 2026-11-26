package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.layout.WidePageContent
import com.dzshzx.zeta.ui.layout.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

// 页面骨架照 Eta 的 ScaffoldPage.kt、AdaptiveTopAppBar.kt（HyperOS 4 的玻璃顶栏）：手机上大标题随上滑折叠，宽屏时是固定的小标题、
// 内容限在居中的最大宽度内；顶栏是渐进模糊的玻璃，模糊的是录进页面背景（[rememberPageBackdrop]）的页面。
// Zeta 的改动：窗口有焦点时才给键盘让位，免得输入对话框打开时背景页面提前空出键盘高度；顶栏的按钮槽提供 LocalInGlassTopBar，
// 里面的 IconActionButton 自己画成玻璃圆钮。

/** 顶栏样式。 */
enum class MiuixTopBarStyle {
    /** 大标题，上滑折叠；宽屏时是固定的小顶栏。 */
    Large,

    /** 始终是固定的小顶栏。 */
    Small,
}

/** 页面内容排版要用的东西：留白（已含顶栏、导航栏和宽屏两侧）、顶栏的滚动联动。 */
@Stable
class MiuixPageContentScope(
    val contentPadding: PaddingValues,
    val scrollBehavior: ScrollBehavior,
)

/** 页面列表都要的滚动修饰：滚到头的触感、越界回弹（回弹量交给顶栏，玻璃跟着显出）、和顶栏折叠联动。 */
fun Modifier.miuixPageScroll(scope: MiuixPageContentScope): Modifier {
    val state = scope.scrollBehavior.state
    return this.scrollEndHaptic()
        .overScrollVertical(onOffsetChanged = { state.overscrollOffset = it })
        .nestedScroll(scope.scrollBehavior.nestedScrollConnection)
        .fillMaxHeight()
}

/** Miuix 页面：玻璃顶栏；页面录进背景图（[rememberPageBackdrop]），顶栏和页面上弹出的对话框、菜单拿它做毛玻璃。内容随意排，列表页用 [MiuixPage]。 */
@Composable
fun MiuixPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    topBarStyle: MiuixTopBarStyle = MiuixTopBarStyle.Large,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable () -> Unit = {},
    isContentScrolled: Boolean = false,
    content: @Composable (MiuixPageContentScope) -> Unit,
) {
    val pageBackdrop = rememberPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imePadding = if (windowFocused) Modifier.imePadding() else Modifier
    // 键盘弹出时页面已经让到键盘上面，导航栏在键盘后面，底部不再留导航栏的高度
    val imeShown = windowFocused && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        modifier = modifier.fillMaxSize().then(imePadding),
        topBar = {
            MiuixPageTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                backdrop = pageBackdrop,
                onBack = onBack,
                style = topBarStyle,
                isContentScrolled = isContentScrolled,
                actions = actions,
                bottomContent = topBarBottomContent,
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().pageBlurSource(pageBackdrop)) {
            WidePageContent { sidePadding ->
                val top = innerPadding.calculateTopPadding() + 12.dp
                val bottom =
                    if (imeShown) 0.dp else max(innerPadding.calculateBottomPadding(), navBottom)
                val start = innerPadding.calculateStartPadding(layoutDirection) + sidePadding
                val end = innerPadding.calculateEndPadding(layoutDirection) + sidePadding
                val scope =
                    remember(top, bottom, start, end, scrollBehavior) {
                        MiuixPageContentScope(
                            PaddingValues(start = start, top = top, end = end, bottom = bottom),
                            scrollBehavior,
                        )
                    }
                PageBlurHost(pageBackdrop) { content(scope) }
            }
        }
    }
}

/**
 * 内容是一个列表的 [MiuixPageScaffold]：带页面滚动行为和滚动条，末尾留 32dp。
 *
 * @param dialogs 页面的对话框和底部面板。放在这里、和列表并排，才拿得到页面背景，也不会在所在的列表项滚出屏幕时被销毁。
 */
@Composable
fun MiuixPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    dialogs: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    // 玻璃顶栏照列表实际的位置显出，不只看顶栏折叠了多少
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    MiuixPageScaffold(
        title = title,
        modifier = modifier,
        onBack = onBack,
        actions = actions,
        isContentScrolled = scrolled,
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.miuixPageScroll(scope),
                contentPadding = scope.contentPadding,
            ) {
                content()
                item(key = "miuix_page_tail") { Spacer(Modifier.height(32.dp)) }
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

/**
 * 页面的玻璃顶栏（HyperOS 4）：页面录了背景（[backdrop]，系统不支持运行时着色器时为 null）时是渐进模糊，否则是从页面底色往下淡掉的色带；
 * [isContentScrolled] 时玻璃显出。按钮槽里的 [IconActionButton] 画成玻璃圆钮。
 */
@Composable
private fun MiuixPageTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    backdrop: Backdrop?,
    onBack: (() -> Unit)?,
    style: MiuixTopBarStyle,
    isContentScrolled: Boolean,
    actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalInGlassTopBar provides true) {
        GlassTopAppBar(
            title = title,
            isContentScrolled = isContentScrolled,
            expandable = style == MiuixTopBarStyle.Large && !rememberIsWideScreen(),
            progressiveBlur = backdrop != null,
            backdrop = backdrop,
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                if (onBack != null) {
                    IconActionButton(
                        MiuixIcons.Glass.ChevronBackward,
                        Strings.commonBack(),
                        onClick = onBack,
                    )
                }
            },
            actions = actions,
            bottomContent = bottomContent,
        )
    }
}
