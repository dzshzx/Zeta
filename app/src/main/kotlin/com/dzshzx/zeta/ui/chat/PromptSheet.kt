package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ElicitationRequest
import com.dzshzx.zeta.core.PermissionReply
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.Question
import com.dzshzx.zeta.core.UserPrompt
import com.dzshzx.zeta.core.describeSuggestions
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.coui2.DialogContentPadding
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
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
import kotlinx.serialization.json.JsonObject

/**
 * Claude Code 等用户回应时从底部弹出，和终端版的几个对话框一一对应（选项和交回去的应答见 core/Prompts.kt）： 工具确认、计划审批、AskUserQuestion，以及
 * MCP 服务器的表单（[ElicitationSheet]）。一次只弹一个，答完弹下一个。 只有明确点按钮才算回应：误触外面或按返回不替用户做决定。要放在 COUI 的 Scaffold
 * 里（聊天页的内容里），对话框才显示得出来。
 */
@Composable
internal fun PromptSheet(
    prompt: UserPrompt?,
    onReply: (PermissionRequest, PermissionReply) -> Unit,
    onElicit: (ElicitationRequest, String, JsonObject?) -> Unit,
    subagentLabel: (String) -> String? = { null },
) {
    // 关闭动画期间仍要显示上一条请求的内容
    val current = rememberRetained(prompt) ?: return
    val visible = prompt != null && prompt.requestId == current.requestId
    when (current) {
        is ElicitationRequest -> ElicitationSheet(visible, current, onElicit)
        is PermissionRequest ->
            when {
                current.questions.isNotEmpty() -> QuestionSheet(visible, current, onReply)
                current.isPlan -> PlanSheet(visible, current, onReply)
                else ->
                    ToolSheet(
                        visible,
                        current,
                        onReply,
                        current.agentId?.let { subagentLabel(it) ?: "后台子代理" },
                    )
            }
    }
}

/** TUI 的工具确认：允许、允许并以后不再问（Claude Code 给了建议时）、拒绝（可以写理由让 Claude 换个做法）。 */
@Composable
private fun ToolSheet(
    visible: Boolean,
    request: PermissionRequest,
    onReply: (PermissionRequest, PermissionReply) -> Unit,
    agent: String?,
) {
    val presentation = presentTool(request.toolName, request.input)
    val always = remember(request.suggestions) { describeSuggestions(request.suggestions) }
    var remember by remember(request.requestId) { mutableStateOf(false) }
    var feedback by remember(request.requestId) { mutableStateOf("") }
    PageDialog(
        show = visible,
        onDismissRequest = {},
        title = "允许这次操作？",
        summary =
            listOfNotNull(presentation.title, agent?.let { "来自子代理：$it" }, request.reason)
                .joinToString("\n"),
        customContent = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (presentation.detail.isNotBlank()) {
                    Box(Modifier.padding(horizontal = DialogContentPadding)) {
                        DetailBlock(presentation.detail)
                    }
                }
                if (always.isNotEmpty()) {
                    CheckboxPreference(
                        title = "以后不再询问",
                        summary = always.joinToString("\n"),
                        checked = remember,
                        onCheckedChange = { remember = it },
                        insideMargin =
                            PaddingValues(horizontal = DialogContentPadding, vertical = 10.dp),
                    )
                }
                DialogTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    modifier = Modifier.padding(horizontal = DialogContentPadding, vertical = 4.dp),
                    hint = "拒绝时告诉 Claude 换个做法（可不填）",
                    singleLine = false,
                    maxLines = 4,
                )
            }
        },
    ) {
        DialogButtonBar(
            negative =
                DialogButtonBarAction(
                    if (feedback.isBlank()) "拒绝" else "拒绝并告诉 Claude",
                    onClick = { onReply(request, PermissionReply.Deny(feedback)) },
                ),
            positive =
                DialogButtonBarAction(
                    "允许",
                    recommended = true,
                    onClick = {
                        onReply(
                            request,
                            if (remember) PermissionReply.AllowAlways else PermissionReply.Allow,
                        )
                    },
                ),
        )
    }
}

/** TUI 的计划审批：批准后自动接受改动或逐项确认；不批准就写下要改什么，Claude 接着完善计划。 */
@Composable
private fun PlanSheet(
    visible: Boolean,
    request: PermissionRequest,
    onReply: (PermissionRequest, PermissionReply) -> Unit,
) {
    val presentation = presentTool(request.toolName, request.input)
    var mode by remember(request.requestId) { mutableStateOf("acceptEdits") }
    var feedback by remember(request.requestId) { mutableStateOf("") }
    PageDialog(
        show = visible,
        onDismissRequest = {},
        title = "按这个计划执行？",
        customContent = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (presentation.detail.isNotBlank()) {
                    StaticMarkdown(
                        content = presentation.detail,
                        tone = MarkdownTone.Thinking,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = DialogContentPadding, vertical = 4.dp),
                    )
                }
                val margin = PaddingValues(horizontal = DialogContentPadding, vertical = 10.dp)
                RadioButtonPreference(
                    title = "批准，自动接受改动",
                    selected = mode == "acceptEdits",
                    onClick = { mode = "acceptEdits" },
                    radioButtonLocation = RadioButtonLocation.End,
                    insideMargin = margin,
                )
                RadioButtonPreference(
                    title = "批准，改动逐项确认",
                    selected = mode == "default",
                    onClick = { mode = "default" },
                    radioButtonLocation = RadioButtonLocation.End,
                    insideMargin = margin,
                )
                DialogTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    modifier = Modifier.padding(horizontal = DialogContentPadding, vertical = 4.dp),
                    hint = "不批准：告诉 Claude 要改什么",
                    singleLine = false,
                    maxLines = 4,
                )
            }
        },
    ) {
        DialogButtonBar(
            negative =
                DialogButtonBarAction(
                    "继续完善",
                    enabled = feedback.isNotBlank(),
                    onClick = { onReply(request, PermissionReply.Deny(feedback)) },
                ),
            positive =
                DialogButtonBarAction(
                    "批准",
                    recommended = true,
                    onClick = { onReply(request, PermissionReply.ApprovePlan(mode)) },
                ),
        )
    }
}

@Composable
internal fun DetailBlock(text: String) {
    val shape = RoundedCornerShape(COUIRoundCorner.M)
    val colors = COUITheme.colorScheme
    Text(
        text = if (text.length > 4000) text.take(4000) + "\n…" else text,
        style = COUITheme.textStyles.bodyXS.copy(fontFamily = MonoFont),
        color = colors.labelPrimaryVariant,
        modifier =
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .heightIn(max = 260.dp)
                .clip(shape)
                .background(colors.container4, shape)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** 一个问题的作答：选中的选项标签，加上自己写的（TUI 的“Type something”）和备注。 */
private class QuestionAnswer {
    var chosen by mutableStateOf<Set<String>>(emptySet())
    var other by mutableStateOf("")
    var otherOn by mutableStateOf(false)
    var notes by mutableStateOf("")

    /** 交回去的回答：多选题按选项顺序用 ", " 连起来，自己写的排在最后。 */
    fun text(question: Question): String {
        val picked = question.options.map { it.label }.filter { it in chosen }
        val own = other.trim().takeIf { otherOn && it.isNotEmpty() }
        return (picked + listOfNotNull(own)).joinToString(", ")
    }
}

/** TUI 的 AskUserQuestion：每题选一个或几个，也可以自己写；带预览的单选题显示选中那项的预览、能写备注；也可以先不答，和 Claude 聊聊。 */
@Composable
private fun QuestionSheet(
    visible: Boolean,
    request: PermissionRequest,
    onReply: (PermissionRequest, PermissionReply) -> Unit,
) {
    val state =
        remember(request.requestId) { request.questions.indices.associateWith { QuestionAnswer() } }
    fun answer(index: Int) = state.getValue(index)
    val complete =
        request.questions.indices.all { answer(it).text(request.questions[it]).isNotEmpty() }
    val single = request.questions.singleOrNull()
    fun answers() =
        request.questions
            .withIndex()
            .associate { (i, q) -> q.question to answer(i).text(q) }
            .filterValues { it.isNotEmpty() }
    fun notes() =
        request.questions
            .withIndex()
            .associate { (i, q) -> q.question to answer(i).notes }
            .filterValues { it.isNotBlank() }
    PageDialog(
        show = visible,
        onDismissRequest = {},
        title = single?.question ?: "需要你的选择",
        customContent = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                request.questions.forEachIndexed { index, question ->
                    if (single == null) {
                        Text(
                            text = question.question,
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                            modifier =
                                Modifier.padding(
                                    start = DialogContentPadding,
                                    end = DialogContentPadding,
                                    top = if (index == 0) 0.dp else 12.dp,
                                    bottom = 4.dp,
                                ),
                        )
                    }
                    QuestionOptions(question, answer(index))
                }
                Text(
                    text = "先不回答，和 Claude 聊聊这些问题",
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.primary,
                    modifier =
                        Modifier.padding(horizontal = DialogContentPadding, vertical = 12.dp)
                            .clickable {
                                onReply(request, PermissionReply.Clarify(answers(), notes()))
                            },
                )
            }
        },
    ) {
        DialogButtonBar(
            negative =
                DialogButtonBarAction(
                    "跳过",
                    onClick = { onReply(request, PermissionReply.Deny(null)) },
                ),
            positive =
                DialogButtonBarAction(
                    "确定",
                    enabled = complete,
                    recommended = true,
                    onClick = { onReply(request, PermissionReply.Answer(answers(), notes())) },
                ),
        )
    }
}

/** 一个问题的选项：单选题是 COUI 单选行，多选题是复选行，最后一行是自己写；行的左右和对话框标题对齐。 */
@Composable
private fun QuestionOptions(question: Question, answer: QuestionAnswer) {
    val multi = question.multiSelect
    question.options.forEach { option ->
        ChoiceRow(
            title = option.label,
            summary = option.description.ifBlank { null },
            multi = multi,
            checked = option.label in answer.chosen && (multi || !answer.otherOn),
        ) {
            if (!multi) {
                answer.chosen = setOf(option.label)
                answer.otherOn = false
            } else if (option.label in answer.chosen) answer.chosen -= option.label
            else answer.chosen += option.label
        }
    }
    ChoiceRow(title = "其他", summary = null, multi = multi, checked = answer.otherOn) {
        answer.otherOn = !multi || !answer.otherOn
        if (!multi) answer.chosen = emptySet()
    }
    if (answer.otherOn) {
        DialogTextField(
            value = answer.other,
            onValueChange = { answer.other = it },
            modifier = Modifier.padding(horizontal = DialogContentPadding, vertical = 4.dp),
            hint = "自己写",
            singleLine = false,
            maxLines = 4,
        )
    }
    if (!multi && !answer.otherOn) ChosenExtras(question, answer)
}

/** 选项的一行：多选是复选行，单选是选项在右边的单选行。 */
@Composable
private fun ChoiceRow(
    title: String,
    summary: String?,
    multi: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val margin = PaddingValues(horizontal = DialogContentPadding, vertical = 10.dp)
    if (multi) {
        CheckboxPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = { onToggle() },
            insideMargin = margin,
        )
    } else {
        RadioButtonPreference(
            title = title,
            summary = summary,
            selected = checked,
            onClick = onToggle,
            radioButtonLocation = RadioButtonLocation.End,
            insideMargin = margin,
        )
    }
}

/** 单选题选中那项的预览（代码、示意），TUI 在选项旁边显示；带预览的单选题可以给选中的写备注。 */
@Composable
private fun ChosenExtras(question: Question, answer: QuestionAnswer) {
    val preview = question.options.firstOrNull { it.label in answer.chosen }?.preview
    if (preview != null) {
        Box(Modifier.padding(horizontal = DialogContentPadding)) { DetailBlock(preview) }
    }
    if (question.takesNotes && answer.chosen.isNotEmpty()) {
        DialogTextField(
            value = answer.notes,
            onValueChange = { answer.notes = it },
            modifier = Modifier.padding(horizontal = DialogContentPadding, vertical = 4.dp),
            hint = "备注（可不填）",
            singleLine = false,
            maxLines = 3,
        )
    }
}
