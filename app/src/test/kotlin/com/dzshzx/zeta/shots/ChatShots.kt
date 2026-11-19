package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.DiffHunk
import com.dzshzx.zeta.core.DiffKind
import com.dzshzx.zeta.core.DiffLine
import com.dzshzx.zeta.core.EditPatch
import com.dzshzx.zeta.core.ThinkingStep
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ChatShots : ShotTest() {
    /** 一轮完整的对话：用户消息、展开的工具步骤（含改文件的红绿对比）、回答。 */
    @Test
    fun conversation() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("改 README 的构建命令", oneTurn)
        shoot("chat", prepare = { onNodeWithText("已完成 3 个步骤").performClick() }) {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }

    private val readme = "/workspace/claude/zeta/README.md"

    private val oneTurn = listOf(
        UserItem("u1", "README 里的构建命令还是 ./gradlew，改成 tools/gradle，顺便看看 CLAUDE.md 要不要同步"),
        WorkItem(
            "w1",
            listOf(
                ThinkingStep("t1", "先找出两个文件里所有直接调用 gradlew 的地方。", running = false, seconds = 3),
                ToolStep(
                    "s1", "Bash",
                    buildJsonObject {
                        put("command", "grep -n gradlew README.md CLAUDE.md")
                        put("description", "找出直接调用 gradlew 的地方")
                    },
                    ToolStatus.Success,
                    "README.md:12:./gradlew :app:assembleDebug",
                ),
                ToolStep("s2", "Read", buildJsonObject { put("file_path", readme) }, ToolStatus.Success, "（README.md 的内容）"),
                ToolStep(
                    "s3", "Edit",
                    buildJsonObject {
                        put("file_path", readme)
                        put("old_string", "./gradlew :app:assembleDebug")
                        put("new_string", "tools/gradle :app:assembleDebug")
                    },
                    ToolStatus.Success,
                    "The file has been updated.",
                    patch = EditPatch(
                        created = false,
                        hunks = listOf(
                            DiffHunk(
                                listOf(
                                    DiffLine(DiffKind.Context, "## 构建", 10, 10),
                                    DiffLine(DiffKind.Context, "", 11, 11),
                                    DiffLine(DiffKind.Removed, "./gradlew :app:assembleDebug", oldLine = 12),
                                    DiffLine(DiffKind.Added, "tools/gradle :app:assembleDebug", newLine = 12),
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
