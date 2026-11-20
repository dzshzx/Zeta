package com.dzshzx.zeta.ui.markdown

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow

/** 流式状态只跨条目挂载保留；已结束且脱离组合的文档交给有界静态缓存。 */
internal class StreamingMarkdownStateCache {
    private val retainedStates = mutableStateMapOf<String, StreamingMarkdownState>()
    val states: Map<String, StreamingMarkdownState> get() = retainedStates

    fun getOrCreate(messageId: String): StreamingMarkdownState =
        retainedStates.getOrPut(messageId) { StreamingMarkdownState() }

    /**
     * 跑到被取消为止：不在 [streamingMessageIds] 里、也没有条目占着（[StreamingMarkdownState.attachContent]）的状态，
     * 一出现就删掉，终态快照交给静态缓存。条目显示流式排版期间占着它，所以回答结束后要等逐字显现收尾才删。
     */
    suspend fun discardWhenUnused(streamingMessageIds: Set<String>) {
        snapshotFlow {
            retainedStates.entries.mapNotNull { (id, state) ->
                id.takeIf { it !in streamingMessageIds && !state.isAttachedToContent }
            }
        }.collect { unused ->
            unused.forEach { id -> retainedStates.remove(id)?.snapshot?.let(staticMarkdownDocumentCache::put) }
        }
    }
}
