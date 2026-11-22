package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** [AlertNotice]：提醒上写什么、给哪些按钮，以及按选项回答时的答案。 */
class AlertNoticeTest {
    private fun question(vararg options: String, multi: Boolean = false) =
        Question("先改哪个？", "顺序", options.map { QuestionOption(it, "") }, multiSelect = multi)

    /** 核心推来的提问：AskUserQuestion 是要用户回答的（asks_input）。 */
    private fun ask(vararg questions: Question) =
        PermissionRequest(
            "q",
            "AskUserQuestion",
            JsonObject(emptyMap()),
            questions.toList(),
            asksInput = true,
        )

    @Test
    fun toolsGetAllowAndDeny() {
        val request =
            PermissionRequest(
                "r",
                "Bash",
                buildJsonObject {
                    put("command", "rm -rf build")
                    put("description", "清掉构建目录")
                },
                emptyList(),
            )
        val notice = AlertNotice.waiting(request)
        assertEquals("需要你确认：清掉构建目录", notice.text)
        assertEquals("清掉构建目录\nrm -rf build", notice.detail)
        assertEquals(listOf(AlertReply.Allow, AlertReply.Deny), notice.replies)
    }

    @Test
    fun planIsOpenedNotApprovedFromTheAlert() {
        val plan =
            PermissionRequest(
                "p",
                "ExitPlanMode",
                buildJsonObject { put("plan", "很长的计划") },
                emptyList(),
            )
        assertEquals(emptyList<AlertReply>(), AlertNotice.replies(plan))
        assertEquals("很长的计划不进提醒", false, AlertNotice.waiting(plan).detail.contains("很长的计划"))
    }

    @Test
    fun onlyASingleSmallChoiceGetsButtons() {
        assertEquals(
            listOf(AlertReply.Answer("界面"), AlertReply.Answer("文档")),
            AlertNotice.replies(ask(question("界面", "文档"))),
        )
        assertEquals(
            "多选",
            emptyList<AlertReply>(),
            AlertNotice.replies(ask(question("a", "b", multi = true))),
        )
        assertEquals(
            "选项超过三个",
            emptyList<AlertReply>(),
            AlertNotice.replies(ask(question("a", "b", "c", "d"))),
        )
        assertEquals(
            "几道题",
            emptyList<AlertReply>(),
            AlertNotice.replies(ask(question("a"), question("b"))),
        )
        assertEquals("需要你回答", AlertNotice.waiting(ask(question("a"), question("b"))).text)
    }

    @Test
    fun answerMapsTheQuestionToTheOption() {
        val request = ask(question("界面", "文档"))
        assertEquals(
            PermissionReply.Answer(mapOf("先改哪个？" to "文档"), emptyMap()),
            AlertNotice.permissionReply(request, AlertReply.Answer("文档")),
        )
        assertEquals(PermissionReply.Allow, AlertNotice.permissionReply(request, AlertReply.Allow))
        // 通知上的“拒绝”写不了理由：和弹层里直接拒绝一样，这一轮停下
        assertEquals(
            PermissionReply.Deny(null),
            AlertNotice.permissionReply(request, AlertReply.Deny),
        )
    }

    @Test
    fun elicitationOnlyOpensTheConversation() {
        // 那一句是核心写好的（helper/prompt）
        val form =
            ElicitationRequest(
                "e1",
                "deploy",
                "发布前确认几项",
                notice = "MCP 服务器 deploy 需要你填写信息",
            )
        val notice = AlertNotice.waiting(form)
        assertEquals("MCP 服务器 deploy 需要你填写信息", notice.text)
        assertEquals("发布前确认几项", notice.detail)
        assertEquals(emptyList<AlertReply>(), notice.replies)
        assertEquals(StepKind.ASKING, AlertNotice.waitingStep(form).kind)
    }
}
