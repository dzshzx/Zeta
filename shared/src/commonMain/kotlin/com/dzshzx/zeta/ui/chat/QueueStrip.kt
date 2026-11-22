package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.conv.ConvMessage
import com.dzshzx.zeta.ui.components.FoldEnter
import com.dzshzx.zeta.ui.components.FoldExit
import com.dzshzx.zeta.ui.components.coui2.FlatIconButton
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme

private val Whitespace = Regex("\\s+")

/** 输入卡片正上方的排队条（交互照 Eta 的 AgentChatQueueStrip）： 点正文撤回到输入框接着改，⚡ 立即插话，× 删掉。多条时默认只露第一条，点计数展开。 */
@Composable
fun QueueStrip(
    queued: List<ConvMessage>,
    steering: Boolean,
    onSteer: () -> Unit,
    onRecall: (ConvMessage) -> Unit,
    onRemove: (ConvMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queued.isEmpty()) return
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatStripCorner) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val multiple = queued.size > 1
    LaunchedEffect(multiple) { if (!multiple) expanded = false }

    Column(modifier = modifier.fillMaxWidth().chatPanel(shape)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueTitle(queued.size, steering, expanded) { expanded = !expanded }
            if (!expanded) {
                Spacer(Modifier.width(6.dp))
                Preview(
                    queued.first(),
                    onClick = { onRecall(queued.first()) },
                    modifier = Modifier.weight(1f),
                )
                Actions(steering, onSteer = onSteer, onRemove = { onRemove(queued.first()) })
            } else {
                Spacer(Modifier.weight(1f))
                // 插话会让排队的消息依次开始，所以只放在标题行上，不分条
                if (!steering) ActionIcon(ZetaIcons.Bolt, "立即插话", colors.primary, onSteer)
            }
        }
        AnimatedVisibility(
            visible = expanded && multiple,
            enter = FoldEnter,
            exit = FoldExit,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 148.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 3.dp),
            ) {
                queued.forEachIndexed { index, message ->
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = colors.divider,
                    )
                    QueuedRow(index, message, onRecall, onRemove)
                }
            }
        }
    }
}

/** 排队条的标题：一个点和“排队中”（插话时是“插话中”）；多条时带条数和折角，点了展开、收起。 */
@Composable
private fun QueueTitle(count: Int, steering: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val colors = COUITheme.colorScheme
    val multiple = count > 1
    val chevron by
        animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "queue-chevron")
    Row(
        modifier =
            Modifier.clip(RoundedCornerShape(8.dp))
                .then(if (multiple) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
        Spacer(Modifier.width(6.dp))
        Text(
            text =
                when {
                    steering -> "插话中"
                    multiple -> "排队中 · $count"
                    else -> "排队中"
                },
            style = COUITheme.textStyles.bodyS,
            fontWeight = FontWeight.Medium,
            color = colors.primary,
        )
        if (multiple) {
            Spacer(Modifier.width(2.dp))
            Icon(
                ZetaIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(14.dp).rotate(chevron),
                tint = colors.primary,
            )
        }
    }
}

/** 展开后的一条排队消息：序号、正文（点了撤回到输入框）和删除键。 */
@Composable
private fun QueuedRow(
    index: Int,
    message: ConvMessage,
    onRecall: (ConvMessage) -> Unit,
    onRemove: (ConvMessage) -> Unit,
) {
    val colors = COUITheme.colorScheme
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = COUITheme.textStyles.bodyXS,
            fontWeight = FontWeight.Medium,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Preview(message, onClick = { onRecall(message) }, modifier = Modifier.weight(1f))
        ActionIcon(COUIIcons.Close, "删除这条排队消息", colors.labelSecondary) { onRemove(message) }
    }
}

@Composable
private fun Preview(message: ConvMessage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val line = message.text.replace(Whitespace, " ").trim()
    val extra = message.images.size + message.files.size
    val text =
        when {
            line.isNotEmpty() -> line
            message.images.isNotEmpty() -> "[${message.images.size} 张图片]"
            else -> "[${message.files.size} 个文件]"
        }
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "撤回到输入框", onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = COUITheme.textStyles.bodyM,
            color = COUITheme.colorScheme.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (line.isNotEmpty() && extra > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                "+$extra",
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun Actions(steering: Boolean, onSteer: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!steering) ActionIcon(ZetaIcons.Bolt, "立即插话", COUITheme.colorScheme.primary, onSteer)
        ActionIcon(COUIIcons.Close, "删除这条排队消息", COUITheme.colorScheme.labelSecondary, onRemove)
    }
}

/** 条上的小图标键：32dp 的平按钮，16dp 图标。 */
@Composable
fun ActionIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) =
    FlatIconButton(
        icon,
        description,
        size = 32.dp,
        iconSize = 16.dp,
        tint = tint,
        onClick = onClick,
    )
