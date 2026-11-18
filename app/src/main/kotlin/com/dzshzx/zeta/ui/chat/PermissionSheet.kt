package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.theme.CouiTheme
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.Question
import com.dzshzx.zeta.ui.components.EtaMonospace
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Claude Code 要求确认时从底部弹出：批准一次工具调用，或回答它提的问题。 */
@Composable
internal fun PermissionSheet(
    request: PermissionRequest?,
    onAllow: (PermissionRequest, Map<String, String>?) -> Unit,
    onDeny: (PermissionRequest) -> Unit,
) {
    // 关闭动画期间仍要显示上一条请求的内容
    val shown = remember { arrayOfNulls<PermissionRequest>(1) }
    if (request != null) shown[0] = request
    val current = shown[0] ?: return
    if (current.questions.isNotEmpty()) {
        QuestionSheet(visible = request != null, request = current, onAllow = onAllow, onDeny = onDeny)
    } else {
        val presentation = presentTool(current.toolName, current.input)
        CouiConfirmDialog(
            visible = request != null,
            title = if (current.toolName == "ExitPlanMode") "按这个计划执行？" else "允许这次操作？",
            message = if (current.toolName == "ExitPlanMode") null else presentation.title,
            confirmText = "允许",
            cancelText = "拒绝",
            onConfirm = { onAllow(current, null) },
            // 只有明确点“拒绝”才算拒绝；误触外面或按返回不替用户做决定
            onCancel = { onDeny(current) },
            onDismissRequest = {},
        ) {
            when {
                presentation.detail.isBlank() -> Unit
                current.toolName == "ExitPlanMode" -> StaticMarkdown(
                    content = presentation.detail,
                    tone = MarkdownTone.Thinking,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                )
                else -> DetailBlock(presentation.detail)
            }
        }
    }
}

@Composable
private fun DetailBlock(text: String) {
    val shape = RoundedCornerShape(12.dp)
    val colors = MiuixTheme.colorScheme
    Text(
        text = if (text.length > 4000) text.take(4000) + "\n…" else text,
        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace, fontSize = 12.sp, lineHeight = 17.sp),
        color = colors.onSurface.copy(alpha = 0.82f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(max = 260.dp)
            .clip(shape)
            .background(colors.onSurface.copy(alpha = if (CouiTheme.isDark) 0.07f else 0.04f), shape)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun QuestionSheet(
    visible: Boolean,
    request: PermissionRequest,
    onAllow: (PermissionRequest, Map<String, String>?) -> Unit,
    onDeny: (PermissionRequest) -> Unit,
) {
    // 每个问题选中的选项标签；多选题可以有多个
    val chosen = remember(request.requestId) { mutableStateMapOf<Int, Set<String>>() }
    val complete = request.questions.indices.all { !chosen[it].isNullOrEmpty() }
    val single = request.questions.singleOrNull()
    CouiConfirmDialog(
        visible = visible,
        title = single?.question ?: "需要你的选择",
        message = null,
        confirmText = "确定",
        cancelText = "跳过",
        confirmEnabled = complete,
        onConfirm = {
            onAllow(request, request.questions.withIndex().associate { (index, q) -> q.question to chosen[index].orEmpty().joinToString(", ") })
        },
        onCancel = { onDeny(request) },
        onDismissRequest = {},
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            request.questions.forEachIndexed { index, question ->
                if (single == null) {
                    Text(
                        text = question.question,
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                    )
                }
                QuestionOptions(question, chosen[index].orEmpty()) { label ->
                    val before = chosen[index].orEmpty()
                    chosen[index] = when {
                        !question.multiSelect -> setOf(label)
                        label in before -> before - label
                        else -> before + label
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionOptions(question: Question, selected: Set<String>, onToggle: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    question.options.forEach { option ->
        val isSelected = option.label in selected
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (isSelected) colors.primary.copy(alpha = 0.10f) else colors.onSurface.copy(alpha = 0.035f), shape)
                .border(if (isSelected) 1.dp else 0.5.dp, if (isSelected) colors.primary else colors.onSurface.copy(alpha = 0.08f), shape)
                .clickable { onToggle(option.label) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = option.label,
                style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp),
                color = if (isSelected) colors.primary else colors.onSurface,
            )
            if (option.description.isNotBlank()) {
                Text(
                    text = option.description,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
