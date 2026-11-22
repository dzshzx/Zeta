package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.conv.ConvSubagent
import com.dzshzx.zeta.core.conv.SubagentMeta
import com.dzshzx.zeta.core.conv.SubagentState
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.chat.SubagentPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** 子代理：聊天里发起它的那一步（点了进查看页），查看页本身在核心给的各个 state 下的样子。 */
class SubagentShots : ShotTest() {
    private val call =
        ToolStep(
            "toolu_A",
            "Agent",
            buildJsonObject {
                put("description", "审查面板排版")
                put("prompt", "看看后台任务面板的排版，列出不对齐和文字溢出的地方。只读代码，不要改。")
                put("subagent_type", "general-purpose")
            },
            ToolStatus.Success,
            "Async agent launched successfully.",
        )

    private fun step(id: String, name: String, input: String, result: String?) =
        ToolStep(
            id,
            name,
            Json.parseToJsonElement(input).jsonObject,
            if (result == null) ToolStatus.Running else ToolStatus.Success,
            result,
        )

    /** 照核心推来的 conv.subagent 摆一份：先说一句，读文件、搜索、跑命令；不在跑时命令跑完了，完成时交回报告。 */
    private fun log(state: SubagentState): ConvSubagent {
        val done = state != SubagentState.Running
        val steps =
            listOf(
                step(
                    "s1",
                    "Read",
                    """{"file_path":"/workspace/claude/zeta/app/src/main/kotlin/com/dzshzx/zeta/ui/tasks/BackgroundTasksScreen.kt"}""",
                    "（文件内容）",
                ),
                step(
                    "s2",
                    "Grep",
                    """{"pattern":"maxLines","path":"app/src/main/kotlin/com/dzshzx/zeta/ui/tasks"}""",
                    "BackgroundTasksScreen.kt:330: maxLines = 1",
                ),
                step(
                    "s3",
                    "Bash",
                    """{"command":"tools/shots BackgroundTask","description":"重画后台任务面板的截图"}""",
                    if (done) "BUILD SUCCESSFUL in 41s" else null,
                ),
            )
        val items = buildList {
            add(AnswerItem("m1", "先读面板的代码，再对着截图看。", streaming = false))
            add(WorkItem("w-s1", steps))
            if (state == SubagentState.Completed)
                add(
                    AnswerItem(
                        "m4",
                        "**两处问题**\n\n1. 任务条第二行在长命令时没有截断到一行。\n2. 详情页「工具调用」的值和标题没有对齐。",
                        streaming = false,
                    ),
                )
        }
        return ConvSubagent(
            toolUseId = "toolu_A",
            meta =
                SubagentMeta(
                    agentId = "a1",
                    agentType = "general-purpose",
                    description = "审查面板排版",
                    model = "claude-opus-5-5",
                    finished = state == SubagentState.Completed,
                    stopped = state == SubagentState.Stopped,
                    background = true,
                ),
            state = state,
            prompt = "看看后台任务面板的排版，列出不对齐和文字溢出的地方。只读代码，不要改。",
            toolUses = 3,
            file = "/home/u/.claude/projects/p/s/subagents/agent-a1.jsonl",
            items = items,
        )
    }

    /** 记录还没读到时：核心先用发起它的那次 Agent 调用的说明、类型和指令填上（agent_id 为空），一步都没有。 */
    private fun unread(state: SubagentState, failure: String? = null) =
        ConvSubagent(
            toolUseId = "toolu_A",
            meta =
                SubagentMeta(agentId = "", agentType = "general-purpose", description = "审查面板排版"),
            state = state,
            prompt = "看看后台任务面板的排版，列出不对齐和文字溢出的地方。只读代码，不要改。",
            failure = failure,
        )

    @Test
    fun running() {
        val log = log(SubagentState.Running)
        shoot("subagent-running") { SubagentPage(log, onBack = {}) }
    }

    @Test
    fun finished() {
        val log = log(SubagentState.Completed)
        shoot("subagent-done") { SubagentPage(log, onBack = {}) }
    }

    @Test
    fun failed() {
        val log = log(SubagentState.Failed)
        shoot("subagent-failed") { SubagentPage(log, onBack = {}) }
    }

    @Test
    fun stopped() {
        val log = log(SubagentState.Stopped)
        shoot("subagent-stopped") { SubagentPage(log, onBack = {}) }
    }

    /** 不在跑又不知道结局：进程没等它做完就没了。 */
    @Test
    fun unfinished() {
        val log = log(SubagentState.Unfinished)
        shoot("subagent-unfinished") { SubagentPage(log, onBack = {}) }
    }

    @Test
    fun loading() {
        val log = unread(SubagentState.Loading)
        shoot("subagent-loading") { SubagentPage(log, onBack = {}) }
    }

    /** 读失败：状态说读取失败，步骤处显示核心给的原因。 */
    @Test
    fun readFailed() {
        val log =
            unread(
                SubagentState.ReadFailed,
                failure = "zeta-helper subagent: exit status 1: permission denied",
            )
        shoot("subagent-read-failed") { SubagentPage(log, onBack = {}) }
    }

    @Test
    fun missing() {
        val log = unread(SubagentState.Missing)
        shoot("subagent-missing") { SubagentPage(log, onBack = {}) }
    }

    /** 聊天里发起子代理的那一步：右边是进入的箭头，不在原地展开。 */
    @Test
    fun chatStep() {
        val hub = Fixtures.readyHub(app)
        hub.showChat(
            "审查后台任务面板",
            listOf(
                UserItem("u1", "让子代理看看后台任务面板的排版"),
                WorkItem(
                    "w1",
                    listOf(
                        ToolStep(
                            "s0",
                            "Bash",
                            buildJsonObject {
                                put("command", "git status -sb")
                                put("description", "看看工作区")
                            },
                            ToolStatus.Success,
                            "## task/x",
                        ),
                        call,
                    ),
                ),
                AnswerItem("a1", "已经交给子代理在后台看，结果出来我再汇总。", streaming = false),
            ),
        )
        shoot("subagent-chat-step", prepare = { onNodeWithText("已完成 2 个步骤").performClick() }) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }
}
