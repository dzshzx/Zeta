package com.dzshzx.zeta.shots

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ChatUsage
import com.dzshzx.zeta.core.ContextLevel
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ElicitField
import com.dzshzx.zeta.core.ElicitationRequest
import com.dzshzx.zeta.core.Occupant
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.Question
import com.dzshzx.zeta.core.QuestionOption
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.TodoEntry
import com.dzshzx.zeta.core.TodoList
import com.dzshzx.zeta.core.TodoStatus
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.UserPrompt
import com.dzshzx.zeta.core.conv.ConvMessage
import com.dzshzx.zeta.core.conv.ConvMode
import com.dzshzx.zeta.core.core
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.core.phone
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

/** 聊天页上的零件：顶栏和输入栏的菜单、查找栏、输入栏上方的几种条、确认与提问、空对话、环境没就绪、会话在别处运行。 */
class ChatScreenPartsShots : ShotTest() {
    @Test
    fun overflowMenu() {
        val hub = chatHub()
        shoot(
            "chat-overflow-menu",
            prepare = { onNodeWithContentDescription("更多").performClick() },
        ) {
            Chat(hub)
        }
    }

    @Test
    fun modelMenu() {
        val hub = chatHub()
        shoot("chat-model-menu", prepare = { onNodeWithText(TITLE).performClick() }) { Chat(hub) }
    }

    @Test
    fun attachMenu() {
        val hub = chatHub()
        shoot(
            "chat-attach-menu",
            prepare = { onNodeWithContentDescription("添加附件").performClick() },
        ) {
            Chat(hub)
        }
    }

    @Test
    fun permissionModeMenu() {
        val hub = chatHub()
        shoot(
            "chat-permission-menu",
            prepare = { onNodeWithContentDescription("权限模式：默认权限").performClick() },
        ) {
            Chat(hub)
        }
    }

    /** 在对话中查找：查找栏顶替输入栏，命中处高亮。 */
    @Test
    fun find() {
        val hub = chatHub()
        shoot(
            "chat-find",
            prepare = {
                onNodeWithContentDescription("更多").performClick()
                settle()
                onNodeWithText("在对话中查找").performClick()
                settle()
                onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("gradle")
            },
        ) {
            Chat(hub)
        }
    }

    /** 进行中：任务清单（展开）和两条排队消息（展开）叠在输入栏上方，发送键换成停止。 */
    @Test
    fun strips() {
        val hub = chatHub()
        val conversation = hub.current
        conversation.core {
            it.copy(
                lastActivity = System.currentTimeMillis(),
                busy = true,
                todos = todos,
                queued =
                    listOf(
                        ConvMessage("q1", "顺便把 CLAUDE.md 里的构建命令也核对一遍"),
                        ConvMessage(
                            "q2",
                            "改完跑一下 tools/shots Chat",
                            files = listOf("/workspace/claude/zeta/README.md"),
                        ),
                    ),
            )
        }
        shoot(
            "chat-strips",
            prepare = {
                onNodeWithText("排队中 · 2").performClick()
                onNode(hasContentDescription("任务清单，", substring = true)).performClick()
            },
        ) {
            Chat(hub)
        }
    }

    /**
     * 窄屏（约 375dp）上输入栏底栏最挤的时候：本周额度用了 95%、对话正在运行（发送键换成停止）、上下文用量已知（底栏有上下文圆环），
     * 权限是“自动判断”、思考是“超高”。底栏只有图标按钮，停止键要按原尺寸整个露在屏内。
     */
    @Test
    fun crowdedComposerBar() {
        val hub = chatHub()
        val now = System.currentTimeMillis()
        hub.quota(Backends.LOCAL)
            .force(
                "snapshot",
                QuotaSnapshot(
                    windows =
                        listOf(
                            QuotaWindow(
                                "session",
                                "session",
                                null,
                                0.31,
                                now + 3 * 3_600_000,
                                QuotaSeverity.Normal,
                                label = "5 小时",
                                labelCode = "quotaSession",
                            ),
                            QuotaWindow(
                                "weekly_all",
                                "weekly",
                                null,
                                0.95,
                                now + 2 * 86_400_000,
                                QuotaSeverity.Critical,
                                active = true,
                                label = "每周 · 全部模型",
                                labelCode = "quotaWeeklyAll",
                            ),
                        ),
                    subscription = "max",
                    // 一分钟前的数：打开页面时不去刷新（JVM 上进不了 Linux 环境）
                    updatedAt = now - 60_000,
                ),
            )
        val conversation = hub.current
        conversation.core {
            it.copy(
                lastActivity = now,
                busy = true,
                mode = ConvMode(user = "auto", shown = "auto"),
                effort = "xhigh",
                // 核心算好的比例和档位：压缩点 26.6 万，过了它的八成要提醒
                usage =
                    ChatUsage(
                        contextTokens = 214_300,
                        contextWindow = 400_000,
                        autoCompact = true,
                        compactAt = 266_000,
                        fraction = 214_300f / 400_000,
                        compactFraction = 266_000f / 400_000,
                        level = ContextLevel.Warning,
                    ),
            )
        }
        shoot("chat-composer-crowded") { Chat(hub) }

        val screen = compose.onRoot().getBoundsInRoot()
        val stop = compose.onNodeWithContentDescription("停止").getBoundsInRoot()
        assertTrue("停止键被挤小了：宽 ${stop.width}", stop.width >= 35.5.dp)
        assertTrue(
            "停止键露出了屏幕：$stop，屏幕 $screen",
            stop.left >= screen.left && stop.right <= screen.right,
        )
        compose.onNodeWithContentDescription("上下文用量：53%").assertExists()
        compose.onNodeWithContentDescription("权限模式：自动判断").assertExists()
    }

    /** 这个对话开着后台屏幕：输入栏上方的缩略图条。 */
    @Test
    fun agentDisplay() {
        val hub = chatHub()
        val phone = hub.current.phone
        phone.slot.agentDisplay = fakeScreen()
        phone.slot.agentDisplayApp = "设置"
        shoot("chat-agent-display") { Chat(hub) }
    }

    /** 点开后台屏幕条：全屏查看。 */
    @Test
    fun agentDisplayViewer() {
        val hub = chatHub()
        val phone = hub.current.phone
        phone.slot.agentDisplay = fakeScreen()
        phone.slot.agentDisplayApp = "设置"
        shoot(
            "chat-agent-display-viewer",
            target = isDialog(),
            prepare = { onNodeWithText("后台屏幕").performClick() },
        ) {
            Chat(hub)
        }
    }

    /** 批准一次工具调用：Claude Code 给了“以后不再问”的建议，还说明了为什么要问。 */
    @Test
    fun permission() {
        val hub = chatHub()
        hub.current.showPrompts(
            listOf(
                PermissionRequest(
                    "r1",
                    "Bash",
                    buildJsonObject {
                        put("command", "tools/gradle :app:assembleDebug")
                        put("description", "构建 debug 包")
                    },
                    suggestionLines = listOf("允许 Bash(tools/gradle:*)"),
                ),
            ),
        )
        shoot("chat-permission") { Chat(hub) }
    }

    /** 计划审批：计划正文，批准后用哪种模式，不批准时写要改什么。 */
    @Test
    fun plan() {
        val hub = chatHub()
        hub.current.showPrompts(
            listOf(
                PermissionRequest(
                    "p1",
                    "ExitPlanMode",
                    buildJsonObject {
                        put("plan", "# 改构建命令\n\n1. 改 README\n2. 改 CLAUDE.md\n3. 跑一遍门禁")
                    },
                    isPlan = true,
                ),
            ),
        )
        shoot("chat-plan") { Chat(hub) }
    }

    /** Claude 提问：选了一个带预览的选项，可以写备注；最后一行是自己写。 */
    @Test
    fun question() {
        val hub = chatHub()
        hub.current.showPrompts(
            listOf(
                PermissionRequest(
                    "r2",
                    "AskUserQuestion",
                    buildJsonObject {},
                    listOf(
                        Question(
                            "CLAUDE.md 里的构建命令要一起改吗？",
                            "构建命令",
                            listOf(
                                QuestionOption(
                                    "一起改",
                                    "两处保持一致",
                                    preview = "tools/gradle :app:assembleDebug",
                                ),
                                QuestionOption("先不改", "只改 README"),
                            ),
                            multiSelect = false,
                            takesNotes = true,
                        ),
                    ),
                ),
            ),
        )
        shoot("chat-question", prepare = { onNodeWithText("一起改").performClick() }) { Chat(hub) }
    }

    /** MCP 服务器要填一张表：文字、数字、开关、单选。 */
    @Test
    fun elicitation() {
        val hub = chatHub()
        val request =
            ElicitationRequest(
                "e1",
                "deploy",
                "发布前确认几项",
                fields =
                    listOf(
                        ElicitField(
                            "name",
                            "服务名",
                            "小写字母和短横线",
                            required = true,
                            kind = ElicitField.Kind.Text,
                        ),
                        ElicitField(
                            "count",
                            "实例数",
                            "",
                            required = false,
                            kind = ElicitField.Kind.Integer,
                            default = listOf("2"),
                            min = 1.0,
                            max = 5.0,
                        ),
                        ElicitField(
                            "prod",
                            "发到生产环境",
                            "",
                            required = false,
                            kind = ElicitField.Kind.Toggle,
                            default = listOf("false"),
                        ),
                        ElicitField(
                            "region",
                            "地区",
                            "",
                            required = false,
                            kind = ElicitField.Kind.Single,
                            choices =
                                listOf(
                                    ElicitField.Choice("cn", "中国"),
                                    ElicitField.Choice("us", "美国"),
                                ),
                        ),
                    ),
                notice = "MCP 服务器 deploy 需要你填写信息",
            )
        hub.current.showPrompts(listOf(request))
        shoot("chat-elicitation") { Chat(hub) }
    }

    /** 侧栏开着时等用户回应的请求先不弹（聊天页被推开，对话框在侧栏上没有底），收起侧栏就弹出来。 */
    @Test
    fun promptWaitsForThePaneToClose() {
        val hub = chatHub()
        hub.current.showPrompts(
            listOf(PermissionRequest("r3", "Bash", buildJsonObject { put("command", "ls") })),
        )
        val paneOpen = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            com.dzshzx.zeta.ui.app.ZetaTheme(windowBackdrop = false) {
                ChatScreen(
                    hub = hub,
                    onOpenPane = {},
                    onOpenSettings = {},
                    onOpenInstall = {},
                    onOpenLogin = {},
                    paneOpen = paneOpen.value,
                )
            }
        }
        fun shown() = compose.onAllNodesWithText("拒绝").fetchSemanticsNodes().isNotEmpty()
        assertTrue("侧栏开着时不弹", !shown())
        paneOpen.value = false
        compose.waitForIdle()
        assertTrue("收起侧栏后弹出来", shown())
    }

    /** 新对话：建议和工作目录。 */
    @Test
    fun empty() {
        val hub = Fixtures.readyHub(app)
        shoot("chat-empty") { Chat(hub) }
    }

    /** 新对话里点工作目录：改目录的对话框。 */
    @Test
    fun cwdDialog() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "chat-cwd-dialog",
            prepare = { onNodeWithText(Fixtures.env.defaultCwd).performClick() },
        ) {
            Chat(hub)
        }
    }

    /** 还没有 Linux 环境。 */
    @Test
    fun noEnvironment() {
        val hub = Fixtures.readyHub(app)
        hub.environment.setup = SetupState.NoEnvironment
        shoot("chat-no-environment") { Chat(hub) }
    }

    /** 会话正在别处运行：输入栏换成说明卡，还没查到那边闲不闲。 */
    @Test fun external() = external("chat-external", null)

    /** 另一台设备上的 Zeta 测试版闲着：能接过来。 */
    @Test
    fun externalIdle() =
        external(
            "chat-external-idle",
            Occupant(Occupant.IDLE, app = Occupant.ZETA_DEV, device = "Xiaomi 17"),
        )

    /** 别的电脑经 SSH 开的终端里正在回答：等这一轮结束。 */
    @Test
    fun externalBusy() =
        external(
            "chat-external-busy",
            Occupant("busy", handsOver = false, app = Occupant.TERMINAL, device = "work-pc"),
        )

    /** 本机终端里在等确认：能接过来，那个确认作废。 */
    @Test
    fun externalWaiting() =
        external(
            "chat-external-waiting",
            Occupant(Occupant.WAITING, "Bash: rm -rf build", app = Occupant.TERMINAL, local = true),
        )

    private fun external(name: String, occupant: Occupant?) {
        val hub = chatHub()
        hub.current.core { it.copy(blocked = true, occupant = occupant) }
        shoot(name) { Chat(hub) }
    }

    /** 摆出核心推来的、等用户回应的请求。 */
    private fun Conversation.showPrompts(prompts: List<UserPrompt>) = core {
        it.copy(prompts = prompts)
    }

    @androidx.compose.runtime.Composable
    private fun Chat(hub: ConversationHub) {
        ChatScreen(
            hub = hub,
            onOpenPane = {},
            onOpenSettings = {},
            onOpenInstall = {},
            onOpenLogin = {},
        )
    }

    private fun chatHub(): ConversationHub =
        Fixtures.readyHub(app).also { it.showChat(TITLE, chat) }

    /** 不经 root 的后台屏幕：只有画面接收端，没有真的虚拟屏，缩略图是黑的。 */
    private fun fakeScreen(): VirtualScreen {
        val worker = HandlerThread("shots-screen").apply { start() }
        val reader = ImageReader.newInstance(1220, 2656, PixelFormat.RGBA_8888, 2)
        val process =
            object : Process() {
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

                override fun getInputStream(): InputStream = InputStream.nullInputStream()

                override fun getErrorStream(): InputStream = InputStream.nullInputStream()

                override fun waitFor(): Int = 0

                override fun exitValue(): Int = 0

                override fun destroy() = Unit
            }
        val constructor =
            VirtualScreen::class.java.declaredConstructors.first { it.parameterCount == 7 }
        constructor.isAccessible = true
        return constructor.newInstance(
            7,
            1220,
            2656,
            reader,
            worker,
            Handler(worker.looper),
            process,
        ) as VirtualScreen
    }

    private val todos =
        TodoList(
            listOf(
                TodoEntry(
                    "todo-0",
                    "找出直接调用 gradlew 的地方",
                    "正在找直接调用 gradlew 的地方",
                    TodoStatus.Completed,
                ),
                TodoEntry(
                    "todo-1",
                    "改 README 的构建命令",
                    "正在改 README 的构建命令",
                    TodoStatus.InProgress,
                ),
                TodoEntry("todo-2", "核对 CLAUDE.md", "正在核对 CLAUDE.md", TodoStatus.Pending),
            ),
        )

    private val chat =
        listOf(
            UserItem("u1", "README 里的构建命令还是 ./gradlew，改成 tools/gradle"),
            AnswerItem(
                "a1",
                "改好了：README 第 12 行换成了 `tools/gradle :app:assembleDebug`，CLAUDE.md 里已经是 tools/gradle。",
                streaming = false,
            ),
        )

    private companion object {
        const val TITLE = "改 README 的构建命令"
    }
}
