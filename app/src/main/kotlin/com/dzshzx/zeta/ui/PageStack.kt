package com.dzshzx.zeta.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.dzshzx.zeta.ui.components.SlideInPage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 盖在聊天页上的一页。同一种页面在栈里只有一页。界面重建时整个栈存成 JSON（见 [PageStack.Saver]）。 */
@Serializable
internal sealed interface AppPage {
    @Serializable data object Settings : AppPage

    @Serializable data object Linux : AppPage

    @Serializable data object Ssh : AppPage

    @Serializable data object Install : AppPage

    @Serializable data object Login : AppPage

    /** 后台任务面板：[scope] 是对话的 key（只看它的），或 AllConversations。 */
    @Serializable data class Tasks(val scope: String) : AppPage

    /** 子代理查看页：哪个对话（key）里哪一步（tool_use id）发起的子代理。 */
    @Serializable data class Subagent(val conversation: String, val toolUse: String) : AppPage
}

/**
 * 盖在聊天页上的页面，从下到上排：后开的在上面，画在上面，返回键先关它。
 * 几页可以同时开着（比如设置上面再开 Linux 环境）。
 */
@Stable
internal class PageStack(pages: List<AppPage> = emptyList()) {
    var pages by mutableStateOf(pages)
        private set

    val any: Boolean get() = pages.isNotEmpty()
    val top: AppPage? get() = pages.lastOrNull()

    operator fun contains(page: AppPage): Boolean = page in pages

    /** 打开 [page]，放到最上面。同一种页面已经开着就挪上来（后台任务面板换成新的范围）。 */
    fun open(page: AppPage) {
        pages = pages.filterNot { it::class == page::class } + page
    }

    fun close(page: AppPage) {
        pages = pages - page
    }

    /** 返回键：关掉最上面的一页。 */
    fun pop() {
        pages = pages.dropLast(1)
    }

    /** 全部收起。[keepSetup] 时安装和登录两页留着：它们在进行时不打断。 */
    fun closeAll(keepSetup: Boolean) {
        pages = if (keepSetup) pages.filter { it == AppPage.Install || it == AppPage.Login } else emptyList()
    }

    companion object {
        /** 整个栈存成一段 JSON；读不出来（比如存的是旧格式）就当没有开着的页。 */
        val Saver: Saver<PageStack, String> = Saver(
            save = { stack -> Json.encodeToString(stack.pages) },
            restore = { saved -> PageStack(runCatching { Json.decodeFromString<List<AppPage>>(saved) }.getOrDefault(emptyList())) },
        )
    }
}

@Composable
internal fun rememberPageStack(): PageStack = rememberSaveable(saver = PageStack.Saver) { PageStack() }

/**
 * 要画的页面，从下到上：[pages] 按栈的顺序；[shown]（上一次画的）里已经关掉、还在播退场动画的，
 * 留在它原来所在的位置上（紧挨在原来压在它下面的那页之上）。
 */
internal fun drawOrder(shown: List<AppPage>, pages: List<AppPage>): List<AppPage> {
    val result = pages.toMutableList()
    var anchor = -1
    for (page in shown) {
        if (page in pages) {
            anchor = result.indexOf(page)
        } else {
            anchor += 1
            result.add(anchor, page)
        }
    }
    return result
}

/**
 * 按 [stack] 的顺序把页面一层层盖上去，关掉的页播完退场动画再拿掉。
 * 只有最上面那页里的返回键处理器生效：压在下面的页里开着的子页不抢返回键。
 */
@Composable
internal fun PageStackHost(stack: PageStack, page: @Composable (AppPage) -> Unit) {
    val pages = stack.pages
    // 进入组合时已经开着的页（界面重建后恢复的）直接显示，不再播进场动画
    val restored = remember { pages.toMutableSet() }
    var shown by remember { mutableStateOf(pages) }
    val order = drawOrder(shown, pages)
    SideEffect { if (shown != order) shown = order }
    val top = pages.lastOrNull()
    for (entry in order) {
        key(entry) {
            val visibility = remember { MutableTransitionState(restored.remove(entry)) }
            visibility.targetState = entry in pages
            if (visibility.isIdle && !visibility.currentState) LaunchedEffect(Unit) { shown = shown - entry }
            val back = rememberNavigationEventDispatcherOwner(enabled = entry == top)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back) {
                SlideInPage(visibility) { page(entry) }
            }
        }
    }
}
