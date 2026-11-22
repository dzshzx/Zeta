package com.dzshzx.zeta.ui.pane

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.SessionSummary
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.theme.COUITheme

// 侧栏的多选：选几条会话，一起归档或取消归档、导出、删除。做法照 Eta 模型列表的多选：
// 顶部换成“已选 N 个”和全选，底部换成操作按钮，返回键退出多选。

/** [scope]（当前看得到的那些会话）是不是都选上了。 */
fun allSelected(selected: Set<String>, scope: List<SessionSummary>): Boolean =
    scope.isNotEmpty() && scope.all { it.id in selected }

/** 全选或全不选 [scope]；别处选中的（比如搜索前选的）不动。 */
fun toggleAll(selected: Set<String>, scope: List<SessionSummary>): Set<String> {
    val ids = scope.mapTo(HashSet()) { it.id }
    return if (allSelected(selected, scope)) selected - ids else selected + ids
}

/** 底部那个按钮是归档（true）还是取消归档（false）：选中的全是已归档的才是取消归档。 */
fun archiveAction(chosen: List<SessionSummary>): Boolean =
    chosen.isEmpty() || !chosen.all { it.archived }

/** 选中的会话里能删的：正在进行的（Zeta 里在跑、等确认、后台任务没完，或别处的 Claude Code 开着它）不删。 */
fun deletable(
    chosen: List<SessionSummary>,
    isActive: (String) -> Boolean,
): List<SessionSummary> = chosen.filterNot { it.external || isActive(it.id) }

/**
 * 单条删除的确认框。打开那一刻就定下是只提示（[Blocked]：正在进行，只有“知道了”）还是确认删除（[Confirm]），开着的时候不再变：
 * 否则对话恰好在框开着时结束，“知道了”会在手指下面变成同一位置的“删除”，一点就删掉了。
 */
sealed interface DeletePrompt {
    val session: SessionSummary

    data class Blocked(override val session: SessionSummary) : DeletePrompt

    data class Confirm(override val session: SessionSummary) : DeletePrompt
}

fun deletePrompt(session: SessionSummary, isActive: (String) -> Boolean): DeletePrompt =
    if (deletable(listOf(session), isActive).isEmpty()) DeletePrompt.Blocked(session)
    else DeletePrompt.Confirm(session)

/**
 * 在确认框里点了“删除”以后真正删的那个：只提示的框不删；框开着时才开始进行的（定时任务、通知触发、别处的 Claude Code 接上了它）也不删，
 * 因为删除会结束它的对话进程。批量删除在点“删除”时同样用 [deletable] 再筛一遍。
 */
fun confirmedDeletion(
    prompt: DeletePrompt?,
    isActive: (String) -> Boolean,
): SessionSummary? =
    (prompt as? DeletePrompt.Confirm)?.session?.takeIf {
        deletable(listOf(it), isActive).isNotEmpty()
    }

/** 导出存成的文件内容：一个会话是它的导出原样；几个会话各自照原样，中间用分隔线隔开。 */
fun combineExports(parts: List<ExportedMarkdown>): String =
    parts.singleOrNull()?.markdown
        ?: parts.joinToString("\n---\n\n") { it.markdown.trimEnd() + "\n" }

/** 多选时的顶部：退出多选、已选几个、全选 / 全不选。 */
@Composable
fun SelectionHeader(
    count: Int,
    allSelected: Boolean,
    canSelectAll: Boolean,
    onToggleAll: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaneCircleButton(icon = COUIIcons.Close, description = "退出多选", onClick = onExit)
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (count > 0) "已选 $count 个" else "选择对话",
            style = COUITheme.textStyles.headlineS.copy(fontWeight = FontWeight.SemiBold),
            color = COUITheme.colorScheme.labelPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // 顶栏上的文字按钮：主题色，无底色
        TextButton(
            text = if (allSelected) "全不选" else "全选",
            onClick = onToggleAll,
            enabled = canSelectAll,
            minWidth = 0.dp,
            minHeight = 40.dp,
            colors = ButtonDefaults.textButtonColorsBorderless(),
            textStyle = COUITheme.textStyles.buttonM,
        )
    }
}

/** 多选时的底部：归档或取消归档、导出、删除（红色）。没选中时都不能点。 */
@Composable
fun SelectionActions(
    count: Int,
    archive: Boolean,
    onArchive: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = count > 0
    // 侧栏窄，三个按钮并排时“取消归档”要放得下：不要 COUI 按钮默认的 132dp 最小宽度，左右留白也收窄
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    val error = COUITheme.colorScheme.error
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            text = if (archive) "归档" else "取消归档",
            onClick = onArchive,
            enabled = enabled,
            minWidth = 0.dp,
            insideMargin = padding,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = "导出",
            onClick = onExport,
            enabled = enabled,
            minWidth = 0.dp,
            insideMargin = padding,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = "删除",
            onClick = onDelete,
            enabled = enabled,
            minWidth = 0.dp,
            insideMargin = padding,
            colors =
                ButtonDefaults.textButtonColorsPrimary(
                    color = error,
                    disabledColor = COUITheme.colorScheme.errorDisabled,
                ),
            modifier = Modifier.weight(1f),
        )
    }
}
