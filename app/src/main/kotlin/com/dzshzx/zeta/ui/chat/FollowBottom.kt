package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 贴底跟随：[following] 时内容一长出屏幕就滚到最底端。 用户往上拖就停下；拖完、或者滚动停下时在最底端（而且列表确实滚得动）就恢复。程序跳到别处（接上更早的记录、查找跳转）前调
 * [pause]， 发消息、点「回到底部」时调 [resume]。聊天页和子代理查看页共用。
 */
@Stable
internal class FollowBottom(private val listState: LazyListState, following: Boolean) {
    var following by mutableStateOf(following)
        private set

    fun pause() {
        following = false
    }

    fun resume() {
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
                is DragInteraction.Cancel -> if (!listState.canScrollForward) following = true
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
            .collect { (scrolling, atEnd) -> if (!scrolling && atEnd) following = true }
    }

    /** 跟着的时候，内容一长出屏幕就滚到最底端。 */
    private suspend fun scrollWhileFollowing() {
        snapshotFlow { following }
            .collectLatest { on ->
                if (on)
                    snapshotFlow { listState.canScrollForward }
                        .collect { canForward -> if (canForward) listState.scrollToEnd() }
            }
    }
}

/** 挂在 [listState] 上的贴底跟随；[following] 是一开始跟不跟。 */
@Composable
internal fun rememberFollowBottom(
    listState: LazyListState,
    following: Boolean = true,
): FollowBottom {
    val follow = remember(listState) { FollowBottom(listState, following) }
    LaunchedEffect(follow) { follow.run() }
    return follow
}

/**
 * 滚到内容的最底端。最后一项不在屏上时先定位到它，再往下滚到底：只定位不够，它比一屏还高时会停在它的顶部。
 * 最后一项已在屏上（回答流式输出、贴底跟随时就是这样，每长一行来一次）时直接往下滚：定位会多强制测量一遍整个列表。
 */
internal suspend fun LazyListState.scrollToEnd() {
    val last = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) scrollToItem(last)
    scrollBy(1_000_000f)
}
