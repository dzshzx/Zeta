package com.dzshzx.zeta.ui.components

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull

enum class ConversationPaneAnchor {
    Closed,
    Open,
}

fun conversationPaneSettledValues(
    state: AnchoredDraggableState<ConversationPaneAnchor>,
    isDragging: () -> Boolean,
): Flow<ConversationPaneAnchor> = snapshotFlow {
    val anchor = state.settledValue
    val position = state.anchors.positionOf(anchor)
    if (!isDragging() && !state.isAnimationRunning && abs(state.offset - position) < 0.5f) {
        anchor
    } else {
        null
    }
    // 必须先让 snapshotFlow 观察到运动中的 null，再过滤；回到原锚点也要重新同步。
}
    .filterNotNull()
