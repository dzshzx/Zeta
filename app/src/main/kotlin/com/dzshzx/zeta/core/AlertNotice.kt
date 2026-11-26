package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.presentTool

/*
 * 一个对话的提醒通知（渠道“完成与确认提醒”）上写什么、带哪些按钮。每个对话一条：停下来等用户时说在等什么，
 * 等的请求没了（批准了、拒绝了、Claude Code 撤回了、进程没了）就撤掉；一轮结束时换成结束的那句。
 * 这里是不碰 Android 的部分，发通知在 Notifier，什么时候发、撤在 TurnAlerts。
 */

/** 提醒上的一个按钮：替用户回答这次 can_use_tool。“拒绝”和弹层里不写理由的拒绝一样，这一轮停下。 */
sealed interface AlertReply {
    val label: String

    /** 锁屏时要先解锁才执行（放行工具、替用户选答案）；拒绝不用。 */
    val unlock: Boolean

    data object Allow : AlertReply {
        override val label: String
            get() = Strings.coreAlertAllow()

        override val unlock = true
    }

    data object Deny : AlertReply {
        override val label: String
            get() = Strings.coreAlertDeny()

        override val unlock = false
    }

    /** 单选题的一个选项。 */
    data class Answer(val option: String) : AlertReply {
        override val label = option
        override val unlock = true
    }
}

/**
 * 停下来等用户时提醒上的内容：[text] 是收起时的一行，[subject] 是其中等的是什么（不带“需要你确认”，超级岛右边用），[detail]
 * 是展开后的全文（命令、问题），[replies] 是按钮。
 */
internal data class WaitingNotice(
    val text: String,
    val subject: String,
    val detail: String,
    val replies: List<AlertReply>,
)

internal object AlertNotice {
    /** 系统在展开的通知上最多画这么多个按钮。 */
    const val MAX_BUTTONS = 3

    /** 展开后的细节最多这么多字，命令、问题再长就点开对话看。 */
    private const val DETAIL_CHARS = 400

    /** 胶囊、常驻通知和提醒共用的那一句：提问和 MCP 表单是“需要你回答”，别的是“需要你确认”。 */
    fun waitingStep(prompt: UserPrompt): Step =
        if (prompt.asksInput) Step(Strings.coreStepNeedsAnswer(), StepKind.ASKING)
        else Step(Strings.coreStepNeedsApproval(), StepKind.CONFIRMING)

    fun waiting(prompt: UserPrompt): WaitingNotice {
        val step = waitingStep(prompt).text
        // MCP 服务器要填表或去网页：通知上只说是哪台服务器、要什么，点开对话处理
        val request =
            prompt as? PermissionRequest
                ?: return WaitingNotice(
                    text = prompt.noticeSaid(),
                    subject = prompt.noticeSaid(),
                    detail = (prompt as ElicitationRequest).message.clipChars(DETAIL_CHARS, "…"),
                    replies = emptyList(),
                )
        if (request.toolName == ASK) {
            val question = request.questions.singleOrNull()?.question?.takeIf { it.isNotBlank() }
            return WaitingNotice(
                text = question?.let { Strings.coreStepWithDetail(step, it) } ?: step,
                subject = question ?: step,
                detail = (question ?: step).clipChars(DETAIL_CHARS, "…"),
                replies = replies(request),
            )
        }
        val presentation = presentTool(request.toolName, request.input)
        // 退出计划模式时细节是整份计划，提醒上只说要你看计划
        val detail = if (request.toolName == PLAN) "" else presentation.detail
        return WaitingNotice(
            text = Strings.coreStepWithDetail(step, presentation.title),
            subject = presentation.title,
            detail =
                listOf(presentation.title, detail)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .clipChars(DETAIL_CHARS, "…"),
            replies = replies(request),
        )
    }

    /**
     * 提醒上给哪些按钮：一般的工具给“允许”“拒绝”；只有一道单选题、选项不超过 [MAX_BUTTONS] 个的提问，
     * 每个选项一个按钮；多选、几道题、计划（要看完整份）都不给，点开对话处理。
     */
    fun replies(prompt: UserPrompt): List<AlertReply> {
        val request = prompt as? PermissionRequest ?: return emptyList()
        return when (request.toolName) {
            PLAN -> emptyList()
            ASK -> {
                val question = request.questions.singleOrNull()
                if (
                    question == null ||
                        question.multiSelect ||
                        question.options.size !in 1..MAX_BUTTONS ||
                        question.options.any { it.label.isBlank() }
                )
                    emptyList()
                else question.options.map { AlertReply.Answer(it.label) }
            }
            else -> listOf(AlertReply.Allow, AlertReply.Deny)
        }
    }

    /** 按 [reply] 回答 [request] 时交给 [Conversation.reply] 的回应。 */
    fun permissionReply(request: PermissionRequest, reply: AlertReply): PermissionReply =
        when (reply) {
            AlertReply.Allow -> PermissionReply.Allow
            AlertReply.Deny -> PermissionReply.Deny(null)
            is AlertReply.Answer ->
                PermissionReply.Answer(
                    request.questions
                        .singleOrNull()
                        ?.let { mapOf(it.question to reply.option) }
                        .orEmpty(),
                    emptyMap(),
                )
        }

    private const val ASK = "AskUserQuestion"
    private const val PLAN = "ExitPlanMode"
}
