package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.Question
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.coui2.DialogContentPadding
import com.dzshzx.zeta.ui.components.coui2.PageDialog
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.preference.CheckboxPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonLocation
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * Claude Code 要求确认时从底部弹出：批准一次工具调用，或回答它提的问题。
 * 要放在 COUI 的 Scaffold 里（聊天页的内容里），对话框才显示得出来。
 */
@Composable
internal fun PermissionSheet(
    request: PermissionRequest?,
    onAllow: (PermissionRequest, Map<String, String>?) -> Unit,
    onDeny: (PermissionRequest) -> Unit,
) {
    // 关闭动画期间仍要显示上一条请求的内容
    val current = rememberRetained(request) ?: return
    if (current.questions.isNotEmpty()) {
        QuestionSheet(visible = request != null, request = current, onAllow = onAllow, onDeny = onDeny)
    } else {
        val presentation = presentTool(current.toolName, current.input)
        val plan = current.toolName == "ExitPlanMode"
        // 只有明确点“拒绝”才算拒绝；误触外面或按返回不替用户做决定
        PageDialog(
            show = request != null,
            onDismissRequest = {},
            title = if (plan) "按这个计划执行？" else "允许这次操作？",
            summary = if (plan) null else presentation.title,
            customContent = if (presentation.detail.isBlank()) {
                null
            } else {
                {
                    Box(Modifier.padding(horizontal = DialogContentPadding)) {
                        if (plan) {
                            StaticMarkdown(
                                content = presentation.detail,
                                tone = MarkdownTone.Thinking,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            )
                        } else {
                            DetailBlock(presentation.detail)
                        }
                    }
                }
            },
        ) {
            DialogButtonBar(
                negative = DialogButtonBarAction("拒绝", onClick = { onDeny(current) }),
                positive = DialogButtonBarAction("允许", recommended = true, onClick = { onAllow(current, null) }),
            )
        }
    }
}

@Composable
private fun DetailBlock(text: String) {
    val shape = RoundedCornerShape(COUIRoundCorner.M)
    val colors = COUITheme.colorScheme
    Text(
        text = if (text.length > 4000) text.take(4000) + "\n…" else text,
        style = COUITheme.textStyles.bodyXS.copy(fontFamily = MonoFont),
        color = colors.labelPrimaryVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(max = 260.dp)
            .clip(shape)
            .background(colors.container4, shape)
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
    // 和工具确认一样：点外面、按返回都不算回答，要明确点“跳过”
    PageDialog(
        show = visible,
        onDismissRequest = {},
        title = single?.question ?: "需要你的选择",
        customContent = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                request.questions.forEachIndexed { index, question ->
                    if (single == null) {
                        Text(
                            text = question.question,
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                            modifier = Modifier.padding(start = DialogContentPadding, end = DialogContentPadding, top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp),
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
        },
    ) {
        DialogButtonBar(
            negative = DialogButtonBarAction("跳过", onClick = { onDeny(request) }),
            positive = DialogButtonBarAction(
                "确定",
                enabled = complete,
                recommended = true,
                onClick = {
                    onAllow(request, request.questions.withIndex().associate { (index, q) -> q.question to chosen[index].orEmpty().joinToString(", ") })
                },
            ),
        )
    }
}

/** 一个问题的选项：单选题是 COUI 单选行，多选题是复选行；行的左右和对话框标题对齐。 */
@Composable
private fun QuestionOptions(question: Question, selected: Set<String>, onToggle: (String) -> Unit) {
    val margin = PaddingValues(horizontal = DialogContentPadding, vertical = 10.dp)
    question.options.forEach { option ->
        val summary = option.description.ifBlank { null }
        if (question.multiSelect) {
            CheckboxPreference(
                title = option.label,
                summary = summary,
                checked = option.label in selected,
                onCheckedChange = { onToggle(option.label) },
                insideMargin = margin,
            )
        } else {
            RadioButtonPreference(
                title = option.label,
                summary = summary,
                selected = option.label in selected,
                onClick = { onToggle(option.label) },
                radioButtonLocation = RadioButtonLocation.End,
                insideMargin = margin,
            )
        }
    }
}
