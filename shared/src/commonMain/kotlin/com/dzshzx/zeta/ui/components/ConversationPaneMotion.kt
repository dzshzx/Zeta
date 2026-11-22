package com.dzshzx.zeta.ui.components

data class ConversationPaneMotion(
    val progress: Float,
    val foregroundOffset: Float,
)

fun conversationPaneMotion(offset: Float, paneWidth: Float): ConversationPaneMotion {
    val width = paneWidth.takeIf { it.isFinite() && it > 0f } ?: 0f
    val displacement = if (offset.isFinite()) offset.coerceIn(0f, width) else 0f
    return ConversationPaneMotion(
        progress = if (width > 0f) displacement / width else 0f,
        foregroundOffset = displacement,
    )
}
