package com.dzshzx.zeta.shots

import com.dzshzx.zeta.core.ProjectUsage
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.SessionUsage
import com.dzshzx.zeta.core.StorageKind
import com.dzshzx.zeta.core.StoragePart
import com.dzshzx.zeta.core.StorageReport
import com.dzshzx.zeta.core.StorageUsage
import com.dzshzx.zeta.ui.settings.ProjectSessionsPage
import com.dzshzx.zeta.ui.settings.StoragePage
import com.dzshzx.zeta.ui.settings.StorageView
import org.junit.Test

/** 设置里的存储页：统计结果是造的（真的要进 Linux 环境），看排版和确认框的文字。 */
class StorageShots : ShotTest() {
    private val projects =
        listOf(
            ProjectUsage("-workspace-claude", "/workspace/claude", 286_000_000, 21),
            ProjectUsage("-home-dzshzx-zeta", "/home/dzshzx/zeta", 18_400_000, 3),
        )

    private val view =
        StorageView(
            ready = true,
            report =
                StorageReport(
                    linux =
                        mapOf(
                            "zeta-files" to StorageUsage(12_500_000, 9_800_000, 4),
                            "zeta-hosts" to StorageUsage(240_000, 180_000, 2),
                            "claude-residue" to
                                StorageUsage(
                                    1_460_000,
                                    1_460_000,
                                    31,
                                    listOf(
                                        StoragePart("file-history", 1_200_000, 12),
                                        StoragePart("session-env", 160_000, 17),
                                        StoragePart("projects", 100_000, 2),
                                    ),
                                ),
                            "claude-debug" to StorageUsage(0),
                            "claude-snapshots" to StorageUsage(28_000, 14_000, 2),
                            "claude-paste" to StorageUsage(0),
                            "claude-image-cache" to StorageUsage(0),
                            "claude-telemetry" to StorageUsage(3_500, 3_500, 1),
                            "apt" to StorageUsage(1_239_000_000, 1_238_000_000, 412),
                            "npm" to StorageUsage(244_000_000, 244_000_000, 2, busy = "npm"),
                            "pip" to StorageUsage(38_800_000, 38_800_000, 3),
                            "tmp" to
                                StorageUsage(
                                    171_000_000,
                                    52_000_000,
                                    9,
                                    listOf(
                                        StoragePart("claude", 50_000_000, 6),
                                        StoragePart("other", 2_000_000, 3),
                                    ),
                                ),
                        ),
                    projects = projects,
                    cleanupPeriodDays = null,
                ),
            appCache = StorageUsage(158_000, 130_000, 1),
            imageMemory = 23_000_000,
            rootfs = 9_885_910_016,
            rootfsDone = true,
            phone = 121_751_144_000L to 500_367_340_000L,
        )

    @Test
    fun page() {
        shoot("storage") {
            StoragePage(
                view,
                onBack = {},
                onRefresh = {},
                onAsk = {},
                onConfirm = {},
                onDismiss = {},
                onOpenProject = {},
            )
        }
    }

    /** “已删会话的残留”的确认框：写明删什么、能腾出多少、什么不删。直接画出来，不滚动（Robolectric 上滚长页面会卡死）。 */
    @Test
    fun confirm() {
        shoot("storage-confirm") {
            StoragePage(
                view.copy(confirming = StorageKind.ClaudeResidue),
                onBack = {},
                onRefresh = {},
                onAsk = {},
                onConfirm = {},
                onDismiss = {},
                onOpenProject = {},
            )
        }
    }

    @Test
    fun loading() {
        shoot("storage-loading") {
            StoragePage(
                StorageView(ready = true, scanning = true, phone = view.phone),
                onBack = {},
                onRefresh = {},
                onAsk = {},
                onConfirm = {},
                onDismiss = {},
                onOpenProject = {},
            )
        }
    }

    @Test
    fun projectSessions() {
        val now = 1_795_100_000_000L
        val ids =
            listOf(
                "aaaaaaaa-1111-4222-8333-444444444444",
                "bbbbbbbb-1111-4222-8333-444444444444",
                "cccccccc-1111-4222-8333-444444444444",
            )
        val sessions =
            listOf(
                SessionUsage(
                    ids[0],
                    "/p/${ids[0]}.jsonl",
                    182_000_000,
                    now - 86_400_000,
                    live = false,
                ),
                SessionUsage(ids[1], "/p/${ids[1]}.jsonl", 64_000_000, now, live = true),
                SessionUsage(
                    ids[2],
                    "/p/${ids[2]}.jsonl",
                    1_200_000,
                    now - 9 * 86_400_000,
                    live = false,
                ),
            )
        val summaries =
            mapOf(
                ids[0] to
                    SessionSummary(
                        ids[0],
                        sessions[0].file,
                        "/workspace/claude",
                        "重写侧栏的多选",
                        "",
                        now,
                    ),
                ids[1] to
                    SessionSummary(
                        ids[1],
                        sessions[1].file,
                        "/workspace/claude",
                        "",
                        "看一下手机的存储都被什么占了",
                        now,
                    ),
            )
        shoot("storage-project") {
            ProjectSessionsPage(
                projects.first().copy(sessions = 3),
                sessions,
                40_000,
                failed = false,
                summaries = summaries,
                onBack = {},
                onOpen = {},
            )
        }
    }
}
