package com.dzshzx.zeta.ui.pane

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiButtonRole
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 侧栏的多选：选几条会话，一起归档或取消归档、导出、删除。做法照 Eta 模型列表的多选：
// 顶部换成“已选 N 个”和全选，底部换成操作按钮，返回键退出多选。

/** [scope]（当前看得到的那些会话）是不是都选上了。 */
internal fun allSelected(selected: Set<String>, scope: List<SessionSummary>): Boolean =
    scope.isNotEmpty() && scope.all { it.id in selected }

/** 全选或全不选 [scope]；别处选中的（比如搜索前选的）不动。 */
internal fun toggleAll(selected: Set<String>, scope: List<SessionSummary>): Set<String> {
    val ids = scope.mapTo(HashSet()) { it.id }
    return if (allSelected(selected, scope)) selected - ids else selected + ids
}

/** 底部那个按钮是归档（true）还是取消归档（false）：选中的全是已归档的才是取消归档。 */
internal fun archiveAction(chosen: List<SessionSummary>): Boolean = chosen.isEmpty() || !chosen.all { it.archived }

/** 选中的会话里能删的：正在进行的（Zeta 里在跑、等确认、后台任务没完，或别处的 Claude Code 开着它）不删。 */
internal fun deletable(chosen: List<SessionSummary>, isActive: (String) -> Boolean): List<SessionSummary> =
    chosen.filterNot { it.external || isActive(it.id) }

/** 几个会话导出到一个 Markdown 文件：各自的导出照原样，中间用分隔线隔开。 */
internal fun combineExports(parts: List<ExportedMarkdown>): String =
    parts.joinToString("\n---\n\n") { it.markdown.trimEnd() + "\n" }

/** 多个会话一起导出时的文件名：Zeta-N个对话-年月日-时分.md。 */
internal fun batchExportFileName(count: Int, now: Long = System.currentTimeMillis()): String =
    "Zeta-${count}个对话-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".md"

/** 多选时的顶部：退出多选、已选几个、全选 / 全不选。 */
@Composable
internal fun SelectionHeader(count: Int, allSelected: Boolean, canSelectAll: Boolean, onToggleAll: () -> Unit, onExit: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(CouiTheme.isDark)
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaneCircleButton(icon = Icons.Rounded.Close, description = "退出多选", onClick = onExit)
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (count > 0) "已选 $count 个" else "选择对话",
            style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.SemiBold),
            color = colors.title,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (allSelected) "全不选" else "全选",
            style = MiuixTheme.textStyles.subtitle,
            color = if (canSelectAll) CouiTheme.colorScheme.accent else colors.summary.copy(alpha = 0.5f),
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canSelectAll, role = Role.Button, onClick = onToggleAll)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** 多选时的底部：归档或取消归档、导出、删除（红色）。没选中时都不能点。 */
@Composable
internal fun SelectionActions(
    count: Int,
    archive: Boolean,
    onArchive: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = count > 0
    // 侧栏窄，三个按钮并排时“取消归档”要放得下
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CouiButton(text = if (archive) "归档" else "取消归档", onClick = onArchive, enabled = enabled, contentPadding = padding, modifier = Modifier.weight(1f))
        CouiButton(text = "导出", onClick = onExport, enabled = enabled, contentPadding = padding, modifier = Modifier.weight(1f))
        CouiButton(
            text = "删除",
            onClick = onDelete,
            enabled = enabled,
            role = CouiButtonRole.DestructiveFilled,
            contentPadding = padding,
            modifier = Modifier.weight(1f),
        )
    }
}
