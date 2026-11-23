package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.DiffHunk
import com.dzshzx.zeta.core.DiffKind
import com.dzshzx.zeta.core.DiffLine
import com.dzshzx.zeta.core.EditPatch
import com.dzshzx.zeta.core.ThoughtItem
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.core
import com.dzshzx.zeta.core.voice.ReadAloud
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatShots : ShotTest() {
    /** 一轮完整的对话：用户消息、展开的工具步骤（含改文件的红绿对比）、回答。 */
    @Test
    fun conversation() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("改 README 的构建命令", oneTurn)
        shoot("chat", prepare = { onNodeWithText("已完成 3 个步骤").performClick() }) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /**
     * 键盘弹出后新对话的空白页地方不够：内容照原样排、能滚，两排建议卡片一样高（原来最后一排被压扁、图标被裁）。 340dp
     * 高时顶栏、标题、目录、两排卡片和输入栏加起来放不下，是键盘弹出后矮屏上的样子。
     */
    @Test
    fun emptyChatShortKeepsSuggestionSize() {
        val hub = Fixtures.readyHub(app)
        shoot("chat-empty-short") {
            Box(Modifier.height(340.dp)) {
                ChatScreen(
                    hub = hub,
                    onOpenPane = {},
                    onOpenSettings = {},
                    onOpenInstall = {},
                    onOpenLogin = {},
                )
            }
        }
        val first = compose.onNodeWithText("看看这个项目").getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText("接着上次做").getUnclippedBoundsInRoot()
        assertEquals(
            "两排建议卡片要一样高",
            (first.bottom - first.top).value,
            (last.bottom - last.top).value,
            0.5f,
        )
    }

    /** 顶栏的“更多”菜单：新对话、结束对话、在对话中查找、设置。 */
    @Test
    fun moreMenu() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("改 README 的构建命令", oneTurn)
        shoot("chat-more-menu", prepare = { onNodeWithContentDescription("更多").performClick() }) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 结束还在进行的对话：先确认，会停掉在做的事。 */
    @Test
    fun endRunningConversation() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("改 README 的构建命令", oneTurn)
        hub.current.core { it.copy(busy = true) }
        shoot(
            "chat-end-confirm",
            prepare = {
                tapNode(onNodeWithContentDescription("更多"))
                tap("结束对话")
            },
        ) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 正在朗读这段回答：回答下方的朗读键换成主题色的停止键。 */
    @Test
    fun readingAloud() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("改 README 的构建命令", oneTurn)
        @Suppress("UNCHECKED_CAST")
        val state =
            ReadAloud::class
                .java
                .getDeclaredField("mutableState")
                .apply { isAccessible = true }
                .get(ReadAloud) as MutableStateFlow<ReadAloud.State>
        state.value = ReadAloud.State(id = "a1")
        try {
            shoot("chat-reading-aloud") {
                ChatScreen(
                    hub = hub,
                    onOpenPane = {},
                    onOpenSettings = {},
                    onOpenInstall = {},
                    onOpenLogin = {},
                )
            }
        } finally {
            state.value = ReadAloud.State()
        }
    }

    /** 另一个会话发来的消息（会话互通）：靠右的细边框卡片，标题是「来自会话 名字」；短的直接全露，长的收着露前几行、标题行带展开箭头。 */
    @Test
    fun peerMessages() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("会话互通", peerTurns)
        shoot("chat-peer") {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 点长会话消息的标题行：展开全文，箭头朝下。 */
    @Test
    fun peerMessageOpened() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("会话互通", peerTurns)
        shoot(
            "chat-peer-open",
            prepare = { onNodeWithText("来自会话 dotfiles-3c").performClick() },
        ) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    private val peerTurns =
        listOf(
            UserItem("u1", "等 home-repo 那边跑完门禁告诉我"),
            AnswerItem("a1", "好，我等它的消息。", streaming = false),
            UserItem("p1", "门禁过了，可以合入。", peer = "home-repo-e8"),
            AnswerItem("a2", "收到，开始合入。", streaming = false),
            UserItem(
                "p2",
                "合入时 wt land 报了两处问题：\n" +
                    "1. scripts/check.sh 里 gate_go_goos 多跑了一遍 windows 的 vet；\n" +
                    "2. Zeta 的截图测试在合入门禁里被跳过了，但全量门禁会画。\n" +
                    "第一处我已经在 toolbox 修了并用 gate-sync rollout 同步到各仓，第二处是预期行为。\n" +
                    "你那边不用改，等我把 home-repo 合入后再继续。",
                peer = "dotfiles-3c",
            ),
            AnswerItem("a3", "明白，等你合入。", streaming = false),
        )

    private val readme = "/workspace/claude/zeta/README.md"

    private val oneTurn =
        listOf(
            UserItem("u1", "README 里的构建命令还是 ./gradlew，改成 tools/gradle，顺便看看 CLAUDE.md 要不要同步"),
            ThoughtItem("t1", "先找出两个文件里所有直接调用 gradlew 的地方。", streaming = false),
            WorkItem(
                "w1",
                listOf(
                    ToolStep(
                        "s1",
                        "Bash",
                        buildJsonObject {
                            put("command", "grep -n gradlew README.md CLAUDE.md")
                            put("description", "找出直接调用 gradlew 的地方")
                        },
                        ToolStatus.Success,
                        "README.md:12:./gradlew :app:assembleDebug",
                    ),
                    ToolStep(
                        "s2",
                        "Read",
                        buildJsonObject { put("file_path", readme) },
                        ToolStatus.Success,
                        "（README.md 的内容）",
                    ),
                    ToolStep(
                        "s3",
                        "Edit",
                        buildJsonObject {
                            put("file_path", readme)
                            put("old_string", "./gradlew :app:assembleDebug")
                            put("new_string", "tools/gradle :app:assembleDebug")
                        },
                        ToolStatus.Success,
                        "The file has been updated.",
                        patch =
                            EditPatch(
                                created = false,
                                hunks =
                                    listOf(
                                        DiffHunk(
                                            listOf(
                                                DiffLine(DiffKind.Context, "## 构建", 10, 10),
                                                DiffLine(DiffKind.Context, "", 11, 11),
                                                DiffLine(
                                                    DiffKind.Removed,
                                                    "./gradlew :app:assembleDebug",
                                                    oldLine = 12,
                                                ),
                                                DiffLine(
                                                    DiffKind.Added,
                                                    "tools/gradle :app:assembleDebug",
                                                    newLine = 12,
                                                ),
                                                DiffLine(DiffKind.Context, "", 13, 13),
                                            ),
                                        ),
                                    ),
                            ),
                    ),
                ),
            ),
            AnswerItem(
                "a1",
                "改好了：\n\n- `README.md` 第 12 行换成了 `tools/gradle :app:assembleDebug`\n" +
                    "- `CLAUDE.md` 里已经是 `tools/gradle`，不用动\n\n要我顺手跑一遍构建确认吗？",
                streaming = false,
            ),
        )
}
