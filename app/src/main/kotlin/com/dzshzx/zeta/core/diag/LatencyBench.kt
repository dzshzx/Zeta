package com.dzshzx.zeta.core.diag

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.DebianSshShell
import com.dzshzx.zeta.core.LinuxShell
import com.dzshzx.zeta.core.RemoteHost
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 量每条命令从发出到收齐结果要多久：手机本机的 Debian 和设置里的每台远程主机，走的都是应用平常跑命令的那条路 （[ConversationHub.shell]），命令是
 * `true`。几条路轮流跑，网络时好时坏不会只落在一条上。 每条路先跑一次预热（还没连上的这时连上），再复用连接跑 warm 次，登录 shell（[LinuxShell.start]）和不登录
 * （[LinuxShell.startPlain]，zeta-helper 这类命令）各一次；远程的再每次先关掉连接、跑 cold 次（每次重新握手）。 跑完关掉连接。
 *
 * 结果写进 logcat（标签 [TAG]）：每次一行 `sample <连法> <warmup|warm|plain|cold> <毫秒> <退出码>`（连法是
 * local、debian、direct）， 用不了的路一行 `skip`，最后一行 `done`。tools/phone-tests/remote-latency.sh 发起并收集。
 * 只在测试版的 manifest 里登记（app/src/dev），不导出，只有 root、system 发得进来： am broadcast -n
 * com.dzshzx.zeta.dev/com.dzshzx.zeta.core.diag.LatencyBench [--ei warm 20] [--ei cold 5]
 */
class LatencyBench : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hub = ConversationHub.get(context.applicationContext as Application)
        val warm = intent.getIntExtra("warm", 20)
        val cold = intent.getIntExtra("cold", 5)
        thread(name = "zeta-latency-bench", isDaemon = true) {
            runCatching { bench(hub, warm, cold) }.onFailure { Log.e(TAG, "出错", it) }
            Log.i(TAG, "done")
        }
    }

    private fun bench(hub: ConversationHub, warm: Int, cold: Int) {
        val backends = listOf(Backends.LOCAL) + hub.settings.remoteHosts.map { it.id }
        // 刚启动时手机的 Debian 可能还在检查
        val deadline = System.nanoTime() + 30_000_000_000L
        while (backends.any { !hub.usable(it) } && System.nanoTime() < deadline) Thread.sleep(500)
        val ready = backends.filter { hub.usable(it) }
        backends
            .filterNot { it in ready }
            .forEach { Log.w(TAG, "skip ${via(hub, it)} ${hub.unavailable(it)}") }
        ready.forEach { sample(hub, it, "warmup") }
        repeat(warm) {
            ready.forEach {
                sample(hub, it, "warm")
                sample(hub, it, "plain")
            }
        }
        val remote = ready.filter { it != Backends.LOCAL }
        repeat(cold) {
            remote.forEach {
                drop(hub, it)
                sample(hub, it, "cold")
            }
        }
        remote.forEach { drop(hub, it) }
    }

    private fun sample(hub: ConversationHub, backend: String, kind: String) {
        val shell = hub.shell(backend)
        if (shell == null) {
            Log.w(TAG, "skip ${via(hub, backend)} ${hub.unavailable(backend)}")
            return
        }
        val start = System.nanoTime()
        val result = shell.run("true", plain = kind == "plain")
        val ms = (System.nanoTime() - start) / 1_000_000.0
        Log.i(
            TAG,
            "sample ${via(hub, backend)} $kind ${String.format(Locale.ROOT, "%.1f", ms)} ${result.exitCode}",
        )
        if (result.exitCode != 0)
            Log.w(TAG, "stderr ${via(hub, backend)} ${DiagEvents.tail(result.stderr, lines = 2)}")
    }

    /** 关掉到 [backend] 的连接，下一条命令重新握手。 */
    private fun drop(hub: ConversationHub, backend: String) {
        val host = hub.remoteHost(backend) ?: return
        when (host.via) {
            RemoteHost.Via.Direct -> hub.ssh.dropAll()
            RemoteHost.Via.Debian ->
                hub.shell(Backends.LOCAL)?.run(DebianSshShell.closeMasters(listOf(host.target)))
        }
    }

    private fun via(hub: ConversationHub, backend: String): String =
        hub.remoteHost(backend)?.via?.key ?: "local"

    companion object {
        const val TAG = "ZetaLatency"
    }
}
