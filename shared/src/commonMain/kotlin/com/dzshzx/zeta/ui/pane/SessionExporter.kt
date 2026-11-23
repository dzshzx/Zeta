package com.dzshzx.zeta.ui.pane

import androidx.compose.runtime.Composable
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.ChatPlatform

/** 侧栏菜单里的“导出”和“分享”，两样都先让助手把会话记录转成 Markdown。 [saveAll] 是多选时的导出：几个会话存进同一个 .md 文件。 */
class SessionExporter(
    val save: (SessionSummary) -> Unit,
    val share: (SessionSummary) -> Unit,
    val saveAll: (List<SessionSummary>) -> Unit,
)

/** 侧栏的导出和分享，怎么存、怎么分享看平台（[ChatPlatform.rememberSessionExporter]）。多选导出选好了保存位置时调 [onSavedMany]。 */
@Composable
fun rememberSessionExporter(
    hub: ConversationHub,
    onSavedMany: () -> Unit = {},
): SessionExporter = ChatPlatform.current.rememberSessionExporter(hub, onSavedMany)

/** 导出完的提示：[saved] 个导出成了，一共 [total] 个。 */
fun exportedMessage(saved: Int, total: Int): String =
    when {
        saved == 0 -> Strings.sidebarExportFailed()
        total == 1 -> Strings.sidebarExported()
        saved < total -> Strings.sidebarExportedSome(saved, total - saved)
        else -> Strings.sidebarExportedMany(saved)
    }

/** 存成的文件名：一个对话用它的标题（没有就用开头的话），多个是“N个对话”。 */
fun exportName(sessions: List<SessionSummary>): String =
    if (sessions.size == 1) {
        exportFileName(
            sessions[0]
                .title
                .ifBlank { sessions[0].preview.take(40) }
                .ifBlank { Strings.sidebarUntitled() },
        )
    } else {
        exportFileName(Strings.sidebarExportManyName(sessions.size))
    }
