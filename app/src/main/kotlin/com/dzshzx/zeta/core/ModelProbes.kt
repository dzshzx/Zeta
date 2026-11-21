package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * 只为向一台设备上的 Claude Code 要模型清单起的短命进程（[ConversationHub.requestModels]）。清单平常随对话起进程时的 initialize 来，
 * 只放在内存里；设置页要列一台设备的模型、那台上又没有对话起过进程时，就在那台的家目录里经 zeta_host.sh 起一个 Claude Code（[ClaudeProcess]），
 * 只发 initialize：不发消息、不接会话、不存会话、不起 MCP 服务器（参数同 [QuotaProbe.ARGS]）。应答和对话的一样读（[InitializeReport]），
 * 模型清单记进 hub，然后收掉（[ClaudeProcess.stop]：发 end_session，清掉托管目录）。
 * 斜杠命令清单不记：它随工作目录和 MCP 服务器变，这个进程报的不是对话里看到的那份，对话自己的进程起来时会报。
 * 同一台同时只起一个；[TIMEOUT_MS] 内没答、起不来、提前退出都算没要到，照样收掉，下次打开页面再要。只在主线程上用。
 */
internal class ModelProbes(
    private val hub: ConversationHub,
    /** 在哪台上跑：平常是 [ConversationHub.shell]，测试里换成临时 HOME。 */
    private val shellFor: (String) -> LinuxShell? = hub::shell,
) {
    private inner class Probe(val backend: String) {
        lateinit var process: ClaudeProcess
        val timeout = Runnable { finish(this) }
    }

    private val running = HashMap<String, Probe>()
    private val main = Handler(Looper.getMainLooper())

    /** 正在向设备 [backend] 要。 */
    fun probing(backend: String): Boolean = backend in running

    /** 向设备 [backend] 要一次清单：正在要就不再起；没有 shell、进程起不来就算了。 */
    fun probe(backend: String) {
        if (backend in running) return
        val shell = shellFor(backend) ?: return
        val probe = Probe(backend)
        probe.process = runCatching {
            ClaudeProcess(
                shell = shell,
                hostScript = hub.hostScript,
                hostKey = UUID.randomUUID().toString(),
                launch = ClaudeProcess.Launch.Start(shell.home, TITLE, QuotaProbe.ARGS),
                // 回调在后台线程上：交回主线程，控制应答在那里交给请求的回调。投递的时候 process 早已赋值（构造在主线程上）
                onEvent = { event -> main.post { probe.process.answer(event) } },
                onExit = { main.post { finish(probe) } },
                // 断了就算没要到，不重连
                reconnect = false,
            )
        }.getOrElse { return }
        running[backend] = probe
        probe.process.request(controlBody("initialize")) { answer ->
            InitializeReport.of(answer)?.models?.let { hub.modelsReported(backend, it) }
            finish(probe)
        }
        main.postDelayed(probe.timeout, TIMEOUT_MS)
    }

    /** 这一次到此为止（答了、超时、进程没了）：只收一次。进程已经自己退出的，stop 只清掉托管目录。 */
    private fun finish(probe: Probe) {
        if (running[probe.backend] !== probe) return
        running.remove(probe.backend)
        main.removeCallbacks(probe.timeout)
        probe.process.stop()
    }

    companion object {
        /** 等应答最多多久：远程设备的登录 shell 加 Claude Code 起来，平常几秒。 */
        const val TIMEOUT_MS = 45_000L

        /** 托管目录里记的标题（诊断页列运行中的进程时显示）。 */
        private const val TITLE = "模型清单"
    }
}
