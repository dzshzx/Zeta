package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.diag.DiagBundle
import com.dzshzx.zeta.core.diag.DiagEntries
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagScripts
import com.dzshzx.zeta.core.diag.LinuxProbe
import com.dzshzx.zeta.core.diag.LogFile
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.core.exitsWithin
import com.dzshzx.zeta.phone.AccessibilityGuard
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 诊断页上的一行：名字和值。 */
internal data class InfoLine(val label: String, val value: String)

/** 诊断页和诊断包要的数据：环境信息、权限状态、Claude Code 那边的日志、本应用的 logcat。 都会碰 root、Linux 环境或系统，在后台线程调用。 */
internal object Diagnostics {
    /** 进 Linux 环境查版本和日志文件；环境不可用时返回 null。 */
    fun probe(hub: ConversationHub): LinuxProbe? {
        val config = hub.settings.env ?: return null
        if (hub.environment.setup != SetupState.Ready) return null
        val result = runCatching {
            LinuxEnv.run(
                config,
                "bash -c " + LinuxEnv.quote(DiagScripts.PROBE) + " zeta-diag",
                timeoutMs = 45_000,
            )
        }
            .getOrNull()
        if (result == null || result.exitCode != 0) return null
        return DiagScripts.parseProbe(result.stdout)
    }

    /** 一个日志文件的最后 [DiagScripts.TAIL_BYTES] 字节；不在许可的位置或读不出来返回 null。 */
    fun tail(hub: ConversationHub, file: LogFile): String? {
        val config = hub.settings.env ?: return null
        if (!DiagScripts.readable(file.path, config.home, AppVariant.current.linuxName)) return null
        val result =
            runCatching {
                LinuxEnv.run(
                    config,
                    "exec tail -c ${DiagScripts.TAIL_BYTES} -- " + LinuxEnv.quote(file.path),
                    timeoutMs = 30_000,
                )
            }
                .getOrNull() ?: return null
        return result.stdout.takeIf { result.exitCode == 0 }
    }

    /** 本进程的 logcat：`logcat -d` 按本进程的 pid 过滤，外加 crash 缓冲区（应用只读得到自己的）。 不用 `logcat -c`，不清别人的日志。 */
    fun logcat(): String {
        val pid = android.os.Process.myPid()
        fun dump(vararg args: String): String = runCatching {
            val process = ProcessBuilder(listOf("logcat") + args).redirectErrorStream(true).start()
            process.outputStream.close()
            val text = StringBuilder()
            val reader = Thread {
                runCatching { text.append(process.inputStream.bufferedReader().readText()) }
            }
                .apply { start() }
            if (!process.exitsWithin(15_000)) process.destroy()
            reader.join(2_000)
            text.toString()
        }
            .getOrElse { "（读不出来：${it.message}）" }
        return "===== logcat -d --pid=$pid =====\n" +
            dump("-d", "-v", "threadtime", "--pid=$pid", "-t", "5000") +
            "\n===== logcat -d -b crash =====\n" +
            dump("-d", "-v", "threadtime", "-b", "crash", "-t", "500")
    }

    /** 环境信息。[probe] 还没查完时是 null，[probing] 表示正在查。 */
    fun environment(
        context: Context,
        hub: ConversationHub,
        probe: LinuxProbe?,
        probing: Boolean,
    ): List<InfoLine> {
        val env = hub.settings.env
        val pending = if (probing) "查询中" else "查不到"
        return listOf(
            InfoLine("Zeta 版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"),
            InfoLine("包名", context.packageName + if (AppVariant.current.isDev) "（测试版）" else ""),
            InfoLine(
                "Android",
                "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· ${Build.MANUFACTURER} ${Build.MODEL}",
            ),
            InfoLine("系统版本", Build.DISPLAY),
            InfoLine(
                "Root",
                when (hub.environment.setup) {
                    SetupState.Checking -> "检查中"
                    SetupState.NoRoot -> "没有授权"
                    else -> "已授权"
                },
            ),
            InfoLine("Debian 路径", env?.rootfs ?: "未配置"),
            InfoLine("Linux 用户", env?.let { "${it.user}（uid ${it.uid}，${it.home}）" } ?: "未配置"),
            InfoLine("Debian 版本", probe?.debian?.ifEmpty { "查不到" } ?: pending),
            InfoLine("Claude Code", probe?.claude?.ifEmpty { "没有安装" } ?: pending),
            InfoLine("Node", probe?.node?.ifEmpty { "没有安装" } ?: pending),
            // 不自动打开：服务被系统拿掉时只记一笔。在守：服务开过；没在守：服务没开过，或在 Zeta 里关了
            InfoLine(
                "无障碍守护",
                (if (AccessibilityGuard.autoRestore(context)) "自动打开" else "不自动打开") +
                    " · " +
                    if (AccessibilityGuard.armed(context)) "在守" else "没在守",
            ),
        )
    }

    /** 权限健康页那几项的当前状态。 */
    fun permissions(
        context: Context,
        hub: ConversationHub,
        snapshot: PermissionSnapshot?,
    ): List<PermissionItem> = PermissionHealth.items(snapshot, hub)

    /**
     * 导出诊断包：环境与权限、运行日志、本应用的 logcat、Claude Code 那边的错误输出和调试日志（各取最后 64 KB）， 打码后打成一个 zip 写进
     * [uri]（系统“保存到”给的位置）。返回包里有几个文件；失败抛异常。 [known] 是诊断页上已经查到的环境信息，有就不再进环境查一遍。logcat 和进 Linux
     * 环境的几次查询同时跑。
     */
    suspend fun export(
        context: Context,
        hub: ConversationHub,
        uri: Uri,
        known: LinuxProbe? = null,
    ): Int = coroutineScope {
        val logcat = async(Dispatchers.IO) { logcat() }
        val zone = ZoneId.systemDefault()
        val probe = known ?: probe(hub)
        val snapshot = runCatching {
            PermissionHealth.snapshot(context.applicationContext)
        }
            .getOrNull()
        val info = buildString {
            append("Zeta 诊断包\n导出时间：")
                .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now(zone)))
                .append("\n\n")
            append("[环境]\n")
            environment(context, hub, probe, probing = false).forEach {
                append(it.label).append("：").append(it.value).append('\n')
            }
            append("\n[权限]\n")
            // 只写状态，不写说明：说明里有账号邮箱之类不必带出去的东西
            permissions(context, hub, snapshot).forEach { item ->
                append(item.title).append("：").append(item.value).append('\n')
            }
            if (probe == null) append("\n（Linux 环境不可用，没有 Claude Code 那边的日志）\n")
        }
        val log = DiagLog.entries().joinToString("\n") { DiagEntries.format(it, zone) }
        val tails = probe?.let { tails(hub, it.files) }.orEmpty()
        val files = DiagBundle.files(info, log, logcat.await(), tails)
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("打不开要保存的文件")
        ZipOutputStream(stream.buffered()).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        files.size
    }

    /** 一次进环境把这些文件的尾部都取回来。 */
    private fun tails(hub: ConversationHub, files: List<LogFile>): Map<LogFile, String> {
        val config = hub.settings.env ?: return emptyMap()
        val readable = files.filter {
            DiagScripts.readable(it.path, config.home, AppVariant.current.linuxName)
        }
        if (readable.isEmpty()) return emptyMap()
        val command =
            "bash -c " +
                LinuxEnv.quote(DiagScripts.TAILS) +
                " zeta-diag " +
                readable.joinToString(" ") { LinuxEnv.quote(it.path) }
        val result =
            runCatching { LinuxEnv.run(config, command, timeoutMs = 60_000) }.getOrNull()
                ?: return emptyMap()
        val texts = DiagScripts.parseTails(result.stdout)
        return readable.mapNotNull { file -> texts[file.path]?.let { file to it } }.toMap()
    }
}
