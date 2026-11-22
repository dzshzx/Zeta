package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ChatUsage
import com.dzshzx.zeta.core.ContextLevel
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.DiffHunk
import com.dzshzx.zeta.core.DiffKind
import com.dzshzx.zeta.core.DiffLine
import com.dzshzx.zeta.core.EditPatch
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.core.RemoteImage
import com.dzshzx.zeta.core.TokenCounts
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.TurnUsage
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.awaitMain
import com.dzshzx.zeta.core.conv.HostCpu
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.ui.chat.AnswerBlock
import com.dzshzx.zeta.ui.chat.AttachPathDialogs
import com.dzshzx.zeta.ui.chat.BarButton
import com.dzshzx.zeta.ui.chat.ChatImage
import com.dzshzx.zeta.ui.chat.EffortChip
import com.dzshzx.zeta.ui.chat.FileChip
import com.dzshzx.zeta.ui.chat.HostCpuButton
import com.dzshzx.zeta.ui.chat.NoticeRow
import com.dzshzx.zeta.ui.chat.PathAttach
import com.dzshzx.zeta.ui.chat.PermissionModeChip
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.QuotaDialogSection
import com.dzshzx.zeta.ui.chat.TypingIndicator
import com.dzshzx.zeta.ui.chat.UsageButton
import com.dzshzx.zeta.ui.chat.UsageDialog
import com.dzshzx.zeta.ui.chat.UserBubble
import com.dzshzx.zeta.ui.chat.WorkGroup
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * 聊天页里的部件单独画：消息气泡、通知、图片、附件标签、折叠的工作组和文件改动卡片、输入栏右边的用量控件、用量弹窗、 引用文件夹和路径的对话框。部件里的对话框和菜单要在 COUI 2 的
 * Scaffold 里才显示，这里外面包一层。
 */
class ChatPartsShots : ShotTest() {
    private val now = System.currentTimeMillis()

    @Composable
    private fun Host(content: @Composable ColumnScope.() -> Unit) {
        Scaffold(containerColor = COUITheme.colorScheme.background) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }

    private fun ConversationHub.fakeQuota(backend: String = Backends.LOCAL) {
        quota(backend)
            .force(
                "snapshot",
                QuotaSnapshot(
                    windows =
                        listOf(
                            QuotaWindow(
                                "session",
                                "session",
                                null,
                                0.12,
                                now + 3 * 3_600_000 + 20 * 60_000,
                                QuotaSeverity.Normal,
                                label = "5 小时",
                            ),
                            QuotaWindow(
                                "weekly_all",
                                "weekly",
                                null,
                                0.86,
                                now + 2 * 86_400_000 + 9 * 3_600_000,
                                QuotaSeverity.Warning,
                                active = true,
                                label = "每周 · 全部模型",
                            ),
                        ),
                    subscription = "max",
                    updatedAt = now - 60_000,
                ),
            )
    }

    private val usage =
        ChatUsage(
            contextTokens = 214_300,
            contextWindow = 400_000,
            model = "claude-opus-5-5",
            turns =
                listOf(
                    TurnUsage(TokenCounts(1200, 860, 205_000, 7_240, 214_300), 0.41, 18_200),
                    TurnUsage(
                        TokenCounts(300, 1_100, 190_000, 2_100, 193_500),
                        0.0778,
                        6_400,
                        costEstimated = true,
                    ),
                ),
            appliedEffort = "high",
            autoCompact = true,
            compactAt = 266_000,
            compactions = 1,
            // 下面几项是核心算好的
            total = TokenCounts(1_500, 1_960, 395_000, 9_340, 407_800),
            totalCost = 0.4878,
            costEstimated = true,
            fraction = 214_300f / 400_000,
            compactFraction = 266_000f / 400_000,
            level = ContextLevel.Warning,
        )

    /** 用户消息（正在编辑的描边、附件）、回答、通知、图片占位、附件标签、打字中。 */
    @Test
    fun items() {
        Fixtures.readyHub(app)
        shoot("chat-parts-items") {
            Host {
                UserBubble(
                    UserItem(
                        "u1",
                        "把 README 里的构建命令改成 tools/gradle，顺便看看 CLAUDE.md",
                        files = listOf("/workspace/claude/zeta/README.md"),
                    ),
                    editing = true,
                    onEdit = {},
                    onDelete = {},
                    onSave = {},
                )
                AnswerBlock(
                    AnswerItem(
                        "a1",
                        "改好了：`README.md` 第 12 行换成了 `tools/gradle :app:assembleDebug`。",
                        streaming = false,
                    ),
                    cache = remember { StreamingMarkdownStateCache() },
                )
                NoticeRow(NoticeItem("n1", "已中断", error = false))
                NoticeRow(
                    NoticeItem(
                        "n2",
                        "Claude Code 退出了（code 1）：API Error: 529 overloaded",
                        error = true,
                    ),
                )
                Column(
                    Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChatImage(RemoteImage("https://images.example.invalid/diagram.png"))
                    ChatImage(FileImage("/workspace/claude/zeta/missing.png"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FileChip(name = "README.md")
                        FileChip(name = "app/src", folder = true)
                    }
                    TypingIndicator()
                }
            }
        }
    }

    /** 长按自己的消息弹出的菜单。 */
    @Test
    fun userMenu() {
        Fixtures.readyHub(app)
        shoot(
            "chat-parts-user-menu",
            prepare = {
                onNodeWithText("看看磁盘还剩多少", substring = true).performTouchInput { longClick() }
            },
        ) {
            Host {
                UserBubble(UserItem("u1", "看看磁盘还剩多少空间"), onEdit = {}, onDelete = {}, onSave = {})
            }
        }
    }

    /** 折叠着的工作组：摘要下面直接露出前三处文件改动（新文件、长对比、普通修改），多的提示展开。 */
    @Test
    fun workCollapsed() {
        Fixtures.readyHub(app)
        shoot("chat-parts-work") {
            Host { WorkGroup(WorkItem("w1", fileSteps)) }
        }
    }

    /** 输入栏底栏的控件：权限模式按钮（没指定和五种模式各自的字母、颜色）；思考强度（默认、指定）； 用量按钮：上下文圆环，够用、快满、马上要压缩三档各一个颜色。 */
    @Test
    fun usageChips() {
        Fixtures.readyHub(app)
        shoot("chat-parts-chips") {
            Host {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(null) + PermissionModes.map { it.first }).forEach {
                        PermissionModeChip(mode = it, onChange = {})
                    }
                }
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EffortChip(effort = null, applied = "high", onChange = {})
                    EffortChip(effort = "max", applied = "max", onChange = {})
                }
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UsageButton(usage.copy(contextTokens = 120_000), onClick = {})
                    UsageButton(usage, onClick = {})
                    UsageButton(usage.copy(contextTokens = 260_000), onClick = {})
                }
                // 负载标识：还不知道、闲、忙、满载（只放得下两位，写 99）
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HostCpuButton({ null }, onClick = {})
                    HostCpuButton({ HostCpu(7, 8) }, onClick = {})
                    HostCpuButton({ HostCpu(76, 8) }, onClick = {})
                    HostCpuButton({ HostCpu(100, 8) }, onClick = {})
                    UsageButton(usage, onClick = {})
                }
                // 底栏上真实的一排：附件、权限模式、思考强度、负载、上下文，图形一样大、都在圆的正中
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarButton("添加附件", onClick = {}) {
                        Icon(ZetaIcons.Plus, contentDescription = null)
                    }
                    PermissionModeChip(mode = "auto", onChange = {})
                    EffortChip(effort = "high", applied = "high", onChange = {})
                    HostCpuButton({ HostCpu(23, 8) }, onClick = {})
                    UsageButton(usage.copy(contextTokens = 60_000), onClick = {})
                }
            }
        }
    }

    /** 点思考强度弹出的档位菜单。 */
    @Test
    fun effortMenu() {
        Fixtures.readyHub(app)
        shoot(
            "chat-parts-effort-menu",
            prepare = { onNode(hasContentDescription("思考强度", substring = true)).performClick() },
        ) {
            Host {
                Row(Modifier.padding(16.dp)) {
                    EffortChip(effort = "xhigh", applied = "xhigh", onChange = {})
                }
            }
        }
    }

    /** 用量弹窗：订阅额度、上下文、本对话累计、各轮。 */
    @Test
    fun usageDialog() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        shoot("chat-parts-usage") {
            Host {
                UsageDialog(
                    visible = true,
                    usage = usage,
                    skippedHistory = 2,
                    canCompact = true,
                    onCompact = {},
                    onDismissRequest = {},
                    quota = { QuotaDialogSection(hub, Backends.LOCAL, visible = true) },
                )
            }
        }
    }

    /** 引用文件夹：JVM 上没有 root，列不出子目录，也查不了 Linux 里看不看得到。 */
    @Test
    fun attachFolder() {
        val hub = Fixtures.readyHub(app)
        // 查 Linux 里看不看得到在后台线程上跑，等它的结果落到界面上；一直停在“正在查看”就失败
        shoot(
            "chat-parts-attach-folder",
            prepare = { waitForText("Linux 里看不到这个文件夹", substring = true) },
        ) {
            Host {
                val state = remember { PathAttach().apply { openFolder() } }
                AttachPathDialogs(state, hub, hub.current, onAttach = {})
            }
        }
    }

    /** 引用 Linux 路径：输入后去查，JVM 上进不了 Linux 环境，显示查不了。 */
    @Test
    fun attachPath() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "chat-parts-attach-path",
            prepare = {
                onNode(hasSetTextAction()).performTextInput("/workspace/claude/zeta/docs")
            },
        ) {
            Host {
                val state = remember { PathAttach().apply { openPath() } }
                AttachPathDialogs(state, hub, hub.current, onAttach = {})
            }
        }
    }

    /** 等后台线程的结果显示出来：每轮时钟走一帧，5 秒内等不到就失败。 */
    private fun AndroidComposeTestRule<*, *>.waitForText(text: String, substring: Boolean = false) =
        awaitMain(5_000) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private val fileSteps =
        listOf(
            ToolStep(
                "e1",
                "Write",
                buildJsonObject {
                    put("file_path", "/workspace/claude/zeta/tools/hello.sh")
                    put("content", "#!/bin/sh\nset -eu\necho hello\n")
                },
                ToolStatus.Success,
                "File created successfully",
            ),
            ToolStep(
                "e2",
                "Edit",
                buildJsonObject {
                    put("file_path", "/workspace/claude/zeta/app/build.gradle.kts")
                    put("old_string", "x")
                    put("new_string", "y")
                },
                ToolStatus.Success,
                "The file has been updated.",
                patch =
                    EditPatch(
                        created = false,
                        hunks =
                            listOf(
                                DiffHunk(
                                    (1..9).map {
                                        DiffLine(
                                            DiffKind.Context,
                                            "    implementation(libs.dep$it)",
                                            40 + it,
                                            40 + it,
                                        )
                                    },
                                ),
                                DiffHunk(
                                    listOf(
                                        DiffLine(
                                            DiffKind.Removed,
                                            "    versionName = \"0.6.32\"",
                                            oldLine = 88,
                                        ),
                                        DiffLine(
                                            DiffKind.Added,
                                            "    versionName = \"0.6.33\"",
                                            newLine = 88,
                                        ),
                                        DiffLine(DiffKind.Context, "    versionCode = 63", 89, 89),
                                        DiffLine(DiffKind.Context, "}", 90, 90),
                                        DiffLine(DiffKind.Context, "", 91, 91),
                                    ),
                                ),
                            ),
                    ),
            ),
            ToolStep(
                "e3",
                "Edit",
                buildJsonObject {
                    put("file_path", "/workspace/claude/zeta/README.md")
                    put("old_string", "./gradlew :app:assembleDebug")
                    put("new_string", "tools/gradle :app:assembleDebug")
                    put("replace_all", true)
                },
                ToolStatus.Success,
                "The file has been updated.",
            ),
            ToolStep(
                "e4",
                "Edit",
                buildJsonObject {
                    put("file_path", "/workspace/claude/zeta/CLAUDE.md")
                    put("old_string", "a")
                    put("new_string", "b")
                },
                ToolStatus.Success,
                "The file has been updated.",
            ),
        )
}
