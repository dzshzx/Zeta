package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.TodoBoard
import com.dzshzx.zeta.core.TodoEntry
import com.dzshzx.zeta.core.TodoStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 输入卡片上方的任务清单（样子照排队条）：一行显示做到第几项、正在做哪一项，点开是整份清单。
 * 清单来自对话里 Claude 的清单工具调用，重新打开旧会话时从历史里还原。
 * 全部做完、这一轮也结束了就不再显示；× 把这一份收掉，清单再有变化时重新出来。
 */
@Composable
internal fun TodoStrip(items: List<ChatItem>, seed: List<ToolStep>, busy: Boolean, modifier: Modifier = Modifier) {
    val list = remember(items, seed) { TodoBoard.from(items, seed) } ?: return
    val signature = remember(list) { list.entries.joinToString("\u0000") { "${it.key}:${it.status}:${it.content}" }.hashCode() }
    var dismissed by rememberSaveable { mutableStateOf<Int?>(null) }
    if (dismissed == signature || (list.allDone && !busy)) return

    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatStripCorner) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "todo-chevron")
    val current = list.current
    val position = minOf(list.done + if (current != null) 1 else 0, list.total)
    val summary = when {
        list.allDone -> "全部完成"
        current != null -> current.activeForm
        else -> list.next?.let { "下一项：" + it.content } ?: "全部完成"
    }

    Column(modifier = modifier.fillMaxWidth().chatPanel(shape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "收起任务清单" else "展开任务清单") { expanded = !expanded }
                .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
                .semantics { contentDescription = "任务清单，已完成 ${list.done} 项，共 ${list.total} 项。$summary" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(done = list.done, total = list.total, allDone = list.allDone)
            Spacer(Modifier.width(7.dp))
            Text(
                text = "$position/${list.total}",
                style = COUITheme.textStyles.bodyS.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = if (list.allDone) colors.success else colors.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                style = COUITheme.textStyles.bodyM,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 5.dp),
            )
            Icon(
                ZetaIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).rotate(chevron),
                tint = colors.labelSecondary,
            )
            ActionIcon(COUIIcons.Close, "收掉这份任务清单", colors.labelSecondary) { dismissed = signature }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(110)) + shrinkVertically(tween(150)),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 232.dp).verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = colors.divider)
                list.entries.forEach { entry -> TodoRow(entry) }
            }
        }
    }
}

@Composable
private fun TodoRow(entry: TodoEntry) {
    val colors = COUITheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) { StatusMark(entry.status) }
        Spacer(Modifier.width(9.dp))
        Text(
            text = if (entry.status == TodoStatus.InProgress) entry.activeForm else entry.content,
            style = COUITheme.textStyles.bodyS.copy(
                fontWeight = if (entry.status == TodoStatus.InProgress) FontWeight.Medium else FontWeight.Normal,
                textDecoration = if (entry.status == TodoStatus.Completed) TextDecoration.LineThrough else null,
            ),
            color = when (entry.status) {
                TodoStatus.Completed -> colors.labelTertiary
                TodoStatus.InProgress -> colors.labelPrimary
                TodoStatus.Pending -> colors.labelPrimaryVariant
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 完成：绿底对勾；进行中：强调色圆圈里一个实心点；待做：空心圆圈。 */
@Composable
private fun StatusMark(status: TodoStatus) {
    val colors = COUITheme.colorScheme
    when (status) {
        TodoStatus.Completed -> Box(Modifier.size(15.dp).clip(CircleShape).background(colors.success), contentAlignment = Alignment.Center) {
            Icon(COUIIcons.Ok, contentDescription = "已完成", modifier = Modifier.size(11.dp), tint = colors.labelOnColor)
        }
        TodoStatus.InProgress -> Box(Modifier.size(15.dp).border(1.5.dp, colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
        }
        TodoStatus.Pending -> Box(Modifier.size(15.dp).border(1.2.dp, colors.labelTertiary, CircleShape))
    }
}

/** 完成比例的小圆环；全部完成时是绿色对勾。 */
@Composable
private fun ProgressRing(done: Int, total: Int, allDone: Boolean) {
    if (allDone) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(COUITheme.colorScheme.success), contentAlignment = Alignment.Center) {
            Icon(COUIIcons.Ok, contentDescription = null, modifier = Modifier.size(11.dp), tint = COUITheme.colorScheme.labelOnColor)
        }
        return
    }
    val track = COUITheme.colorScheme.container12
    val primary = COUITheme.colorScheme.primary
    val fraction by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, tween(300), label = "todo-progress")
    Canvas(Modifier.size(16.dp)) {
        val stroke = 2.2.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
        if (fraction > 0f) {
            drawArc(primary, -90f, 360f * fraction, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** 清单工具那一步在时间线里的标题。 */
internal fun todoToolTitle(name: String, input: JsonObject?): String {
    fun s(key: String) = input?.str(key)?.takeIf { it.isNotBlank() }
    val id = s("taskId") ?: s("task_id")
    return when (name) {
        "TaskCreate" -> "新建任务：" + (s("subject") ?: s("title") ?: "")
        "TaskUpdate" -> when (s("status")) {
            "in_progress" -> "开始任务 #$id"
            "completed" -> "完成任务 #$id"
            "deleted" -> "删掉任务 #$id"
            else -> "更新任务 #$id"
        }
        "TaskList" -> "查看任务清单"
        "TaskGet" -> "查看任务 #$id"
        else -> {
            val todos = input?.get("todos") as? JsonArray
            if (todos == null) "更新任务清单" else "更新任务清单（${todos.size} 项）"
        }
    }
}
