package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.SubagentLog
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.chat.SubagentPage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** 子代理：聊天里发起它的那一步（点了进查看页），查看页本身（跑着的、做完的）。 */
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

    /** 照 zeta-helper subagent 的输出喂一份记录：先说一句，读文件、搜索、跑命令，[done] 时交回报告。 */
    private fun log(done: Boolean): SubagentLog =
        SubagentLog("toolu_A").apply {
            val finished = if (done) ""","finished":true""" else ""
            val lines = buildList {
                add(
                    """{"type":"zeta_subagent","file":"/home/u/.claude/projects/p/s/subagents/agent-a1.jsonl","agent_id":"a1","meta":{"agentType":"general-purpose","description":"审查面板排版","model":"claude-opus-5-5","requestShape":"background"$finished},"offset":100,"line":9,"more":false,"skipped":0,"reset":false,"idle_ms":0}""",
                )
                add(
                    """{"type":"user","prompt":true,"message":{"content":[{"type":"text","text":"看看后台任务面板的排版，列出不对齐和文字溢出的地方。只读代码，不要改。"}]}}""",
                )
                add(
                    """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"先读面板的代码，再对着截图看。"}]}}""",
                )
                add(
                    """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"s1","name":"Read","input":{"file_path":"/workspace/claude/zeta/app/src/main/kotlin/com/dzshzx/zeta/ui/tasks/BackgroundTasksScreen.kt"}}]}}""",
                )
                add(
                    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"s1","content":"（文件内容）","is_error":false}]}}""",
                )
                add(
                    """{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"s2","name":"Grep","input":{"pattern":"maxLines","path":"app/src/main/kotlin/com/dzshzx/zeta/ui/tasks"}}]}}""",
                )
                add(
                    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"s2","content":"BackgroundTasksScreen.kt:330: maxLines = 1","is_error":false}]}}""",
                )
                add(
                    """{"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"s3","name":"Bash","input":{"command":"tools/shots BackgroundTask","description":"重画后台任务面板的截图"}}]}}""",
                )
                if (done) {
                    add(
                        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"s3","content":"BUILD SUCCESSFUL in 41s","is_error":false}]}}""",
                    )
                    add(
                        """{"type":"assistant","message":{"id":"m4","content":[{"type":"text","text":"**两处问题**\n\n1. 任务条第二行在长命令时没有截断到一行。\n2. 详情页「工具调用」的值和标题没有对齐。"}]}}""",
                    )
                }
            }
            transcript.applyRead(lines.joinToString("\n"))
            publish(running = !done, outcome = if (done) TaskState.Completed else null)
        }

    @Test
    fun running() {
        val log = log(done = false)
        shoot("subagent-running") { SubagentPage(log, call, onBack = {}) }
    }

    @Test
    fun finished() {
        val log = log(done = true)
        shoot("subagent-done") { SubagentPage(log, call, onBack = {}) }
    }

    /** 记录还没读到时：用发起它的那一步里的说明和指令先顶上。 */
    @Test
    fun loading() {
        shoot("subagent-loading") { SubagentPage(SubagentLog("toolu_A"), call, onBack = {}) }
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
