package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.core.diag.LinuxProbe
import com.dzshzx.zeta.core.diag.LogFile
import com.dzshzx.zeta.core.diag.LogKind
import com.dzshzx.zeta.ui.settings.DiagnosticsScreen
import com.dzshzx.zeta.ui.setup.PhoneScreen
import org.junit.Test

class DiagnosticsShots : ShotTest() {
    private val now = System.currentTimeMillis()

    private val probe = LinuxProbe(
        claude = "2.1.330",
        node = "22.11.0",
        debian = "13.1",
        files = listOf(
            LogFile(LogKind.Running, "/home/dzshzx/.cache/zeta-dev/hosts/k1/err.log", 2_048, now - 60_000, "整理下载目录"),
            LogFile(LogKind.Finished, "/home/dzshzx/.cache/zeta-dev/stderr/20261119-095950-0a1b2c3d.log", 312, now - 3_600_000),
            LogFile(LogKind.Debug, "/home/dzshzx/.claude/debug/0a1b2c3d-0000-4000-8000-000000000001.txt", 182_000, now - 7_200_000),
        ),
    )

    private val tail = """
        # zeta: claude 退出码 1，2026-11-19 09:59:50 +0800
        [ERROR] MCP server "github" connection failed: Authorization: Bearer fake-token-1
        Error: spawn npx ENOENT
            at ChildProcess._handle.onexit (node:internal/child_process:285:19)
    """.trimIndent()

    private fun seedLog() {
        DiagLog.clear()
        DiagLog.info(DiagTopic.Env, "Linux 环境可用（/data/local/zeta/debian/rootfs，dzshzx）")
        DiagLog.info(DiagTopic.Claude, "起 Claude 进程（0a1b2c3d）：cwd /workspace/claude，模型 opus，手机工具")
        DiagLog.warn(DiagTopic.Accessibility, "无障碍服务不在系统的启用列表里了（Zeta 启动）")
        DiagLog.info(DiagTopic.Accessibility, "已经 root 把无障碍服务加回启用列表")
        DiagLog.warn(DiagTopic.Phone, "手机工具 tap 失败：没找到「确定」")
        DiagLog.error(DiagTopic.Helper, "helper history 出错（退出码 1）：Traceback (most recent call last):\n  File \"<stdin>\", line 401\nSystemExit: no such line")
        DiagLog.info(DiagTopic.Schedule, "定时任务到点：每天早上的新闻摘要")
        DiagLog.warn(DiagTopic.Claude, "Claude 进程退出（0a1b2c3d），退出码 1\nError: spawn npx ENOENT")
    }

    @Test
    fun diagnostics() {
        val hub = Fixtures.readyHub(app)
        seedLog()
        shoot("diagnostics") { DiagnosticsScreen(hub = hub, onBack = {}, probeLoader = { probe }, tailLoader = { tail }) }
    }

    @Test
    fun runLog() {
        val hub = Fixtures.readyHub(app)
        seedLog()
        shoot("diagnostics-log", prepare = { onNodeWithText("查看运行日志").performClick() }) {
            DiagnosticsScreen(hub = hub, onBack = {}, probeLoader = { probe }, tailLoader = { tail })
        }
    }

    @Test
    fun stderrTail() {
        val hub = Fixtures.readyHub(app)
        // 那一行在屏幕下面：不滚动（顶栏会跟着折叠），直接触发点击
        shoot("diagnostics-tail", prepare = { onNodeWithText("整理下载目录").performSemanticsAction(SemanticsActions.OnClick) }) {
            DiagnosticsScreen(hub = hub, onBack = {}, probeLoader = { probe }, tailLoader = { tail })
        }
    }

    @Test
    fun phoneScreen() {
        val hub = Fixtures.readyHub(app)
        shoot("phone") { PhoneScreen(hub = hub, onBack = {}) }
    }
}
