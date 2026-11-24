package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 贴底跟随：[following] 时内容一长出屏幕就往下滚。 用户往上拖就停下；拖完、或者滚动停下时在最底端（而且列表确实滚得动）就恢复。程序跳到别处（接上更早的记录、查找跳转）前调
 * [pause]。聊天页和子代理查看页共用。
 *
 * 给了 [anchor]（列表里最新一条用户消息的下标）时，跟随默认「到顶为止」：往下滚最多滚到这条消息顶到列表上沿，再长就停下， 和 Claude、ChatGPT
 * 的应用一样，回答时问题留在上面、回答往下长， 不会一直跟着每一截新内容滚动、重排（流式输出时这是一大笔开销）； 它已经在屏幕上面了（比如打开对话落到底以后）就不再跟。发消息时调
 * [followTurn]。 只有点「回到底部」（[resume]）是一直跟到底，直到用户往上拖。
 */
@Stable
class FollowBottom(
    private val listState: LazyListState,
    following: Boolean,
    private val anchor: (() -> Int?)? = null,
) {
    var following by mutableStateOf(following)
        private set

    /** 跟随停在 [anchor] 顶到上沿处；为假时一直跟到底。 */
    private var capped = anchor != null

    /**
     * 发消息那一刻 [anchor] 的值：刚发的消息要过一会儿才进列表，这之前 [anchor] 还是上一条（早就在屏幕上面了），
     * 不能拿它判断「到顶了」，先照常滚到底，等它变了再按到顶为止。
     */
    private var sentAfter: Int? = null

    fun pause() {
        following = false
    }

    /** 点「回到底部」：滚到底，一直跟着，直到用户往上拖。 */
    fun resume() {
        capped = false
        following = true
    }

    /** 发了消息：滚到底，回答变长时跟到这条消息顶到上沿为止。 */
    fun followTurn() {
        capped = anchor != null
        sentAfter = anchor?.invoke()
        following = true
    }

    /** 拖完或停在最底端时恢复跟随，照默认的到顶为止；正跟着时不动（点「回到底部」滚到底停下也走到这里）。 */
    private fun settleFollow() {
        if (following) return
        capped = anchor != null
        following = true
    }

    /** 跟着手势和内容变化走，直到所在的协程被取消。由 [rememberFollowBottom] 挂在界面上。 */
    suspend fun run() = coroutineScope {
        launch { watchDrags() }
        launch { watchSettled() }
        scrollWhileFollowing()
    }

    /** 用户往上拖就停下；拖完时在最底端就恢复。 */
    private suspend fun watchDrags() {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> if (!listState.canScrollForward) settleFollow()
            }
        }
    }

    /** 滚动停下时停在最底端就恢复。 */
    private suspend fun watchSettled() {
        // 内容还不满一屏时不算「停在底部」：子代理查看页一开始不跟，内容还没载入时不能就此跟上
        snapshotFlow {
            listState.isScrollInProgress to
                (!listState.canScrollForward && listState.canScrollBackward)
        }
            .collect { (scrolling, atEnd) -> if (!scrolling && atEnd) settleFollow() }
    }

    /** 跟着的时候，内容一长出屏幕就往下滚（到顶为止时见 [step]）。 */
    private suspend fun scrollWhileFollowing() {
        snapshotFlow { following }
            .collectLatest { on ->
                if (on)
                    snapshotFlow { listState.canScrollForward }
                        .collect { canForward -> if (canForward) step() }
            }
    }

    /** 往下滚一步。到顶为止时：[anchor] 还在屏幕下面（打开对话、刚发的消息还没露出来）就滚到底；在屏幕上就只滚到它顶到上沿， 已经顶到或在屏幕上面了就停止跟随。 */
    private suspend fun step() {
        val index = if (capped) anchor?.invoke() else null
        if (index != null && index == sentAfter) {
            listState.scrollToEnd()
            return
        }
        sentAfter = null
        if (index == null) {
            listState.scrollToEnd()
            return
        }
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: return
        val item = visible.firstOrNull { it.index == index }
        when {
            index < first || (item != null && item.offset <= 0) -> following = false
            item == null -> listState.scrollToEnd()
            // 滚不到这么远时停在最底端（scrollBy 不越界）；滚满了就是顶到上沿，停止跟随：
            // 之后列表一直「还能往下滚」，不会再来一步
            else ->
                if (listState.scrollBy(item.offset.toFloat()) >= item.offset - 0.5f)
                    following = false
        }
    }
}

/** 挂在 [listState] 上的贴底跟随；[following] 是一开始跟不跟，[anchor] 见 [FollowBottom]。 */
@Composable
fun rememberFollowBottom(
    listState: LazyListState,
    following: Boolean = true,
    anchor: (() -> Int?)? = null,
): FollowBottom {
    val follow = remember(listState) { FollowBottom(listState, following, anchor) }
    LaunchedEffect(follow) { follow.run() }
    return follow
}

/**
 * 滚到内容的最底端。最后一项不在屏上时先定位到它，再往下滚到底：只定位不够，它比一屏还高时会停在它的顶部。
 * 最后一项已在屏上（回答流式输出、贴底跟随时就是这样，每长一行来一次）时直接往下滚：定位会多强制测量一遍整个列表。
 */
suspend fun LazyListState.scrollToEnd() {
    val last = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) scrollToItem(last)
    scrollBy(1_000_000f)
}

/** 输入法升起时把聊天列表滚到最新处，不管这时跟不跟随（默认不跟，要打字时总是想看最新的）。 升起过程中列表一帧帧变矮，每变一次等这一帧排完再滚，升完那一帧也滚到；收起时不动。 */
@Composable
fun ScrollToEndOnKeyboard(listState: LazyListState) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(listState, ime, density) {
        var last = 0
        snapshotFlow { ime.getBottom(density) }
            .collectLatest { bottom ->
                val rising = bottom > last
                last = bottom
                if (rising) {
                    withFrameNanos {}
                    listState.scrollToEnd()
                }
            }
    }
}
