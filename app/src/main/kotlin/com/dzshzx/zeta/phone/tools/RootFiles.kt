package com.dzshzx.zeta.phone.tools

import android.os.Process
import com.dzshzx.zeta.core.LinuxEnv
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 以 root 读文件失败。[missing]：文件不存在（应用没装、没用过这项功能）。message 是给人看的原因。 */
internal class RootReadError(message: String, val missing: Boolean = false) : IllegalStateException(message)

/**
 * 以 root 读别的应用私有目录里的文件：字节原样经 su 的标准输出交回 Zeta 的进程，不经文本、不在别处落盘。
 * 剪贴板历史（[ClipboardHistory]）和聊天图片（[ChatImages]）用它。
 */
internal object RootFiles {
    /** 当前 Android 用户的编号，拼 /data/user/<n>、/data/media/<n> 用。 */
    val user: Int get() = Process.myUid() / 100_000

    /** 读 Android 一侧的 [path]，超过 [maxBytes] 不读。读不到时抛出 [RootReadError]。 */
    fun read(path: String, maxBytes: Int, timeoutMs: Long = 20_000): ByteArray {
        val script = "[ -e \"\$2\" ] || exit 3; s=\$(/system/bin/stat -c %s \"\$2\") || exit 5; " +
            "[ \"\$s\" -le \"\$3\" ] || { echo \"\$s\" >&2; exit 4; }; exec /system/bin/cat \"\$2\""
        val process = ProcessBuilder("su", "-c", LinuxEnv.androidScript(script, path, maxBytes.toString())).start()
        runCatching { process.outputStream.close() }
        val out = ByteArrayOutputStream()
        val err = StringBuilder()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.use { it.copyTo(out) } } }
        val errors = thread(isDaemon = true) { runCatching { err.append(process.errorStream.bufferedReader().readText()) } }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            LinuxEnv.kill(process)
            throw RootReadError("读取超时（${timeoutMs / 1000} 秒）：$path")
        }
        reader.join(5_000)
        errors.join(2_000)
        val detail = err.toString().trim().take(200)
        return when (val code = process.exitValue()) {
            0 -> out.toByteArray()
            3 -> throw RootReadError("不存在：$path", missing = true)
            4 -> throw RootReadError("文件太大（$detail 字节，上限 $maxBytes）：$path")
            else -> throw RootReadError("读不了（退出码 $code" + (if (detail.isEmpty()) "" else "，$detail") + "）：$path")
        }
    }

    /** 以 root 在 Android 一侧跑 [script]（$1 是 BusyBox，$2 起是 [arguments]），要求输出以 [DONE] 收尾，返回之前的各行。 */
    fun lines(script: String, arguments: List<String>, timeoutMs: Long = 30_000): List<String> {
        val result = LinuxEnv.runRoot(LinuxEnv.androidScript("$script; echo $DONE", *arguments.toTypedArray()), timeoutMs)
        if (result.exitCode == -1 && result.stderr == "timeout") throw IllegalStateException("列目录超时（${timeoutMs / 1000} 秒）")
        val lines = result.stdout.lines()
        val end = lines.indexOfLast { it.trim() == DONE }
        if (end < 0) {
            throw IllegalStateException(
                "以 root 列目录失败（退出码 ${result.exitCode}）" + result.stderr.trim().take(200).let { if (it.isEmpty()) "" else "：$it" },
            )
        }
        return lines.subList(0, end)
    }

    private const val DONE = "ZETA_ROOT_DONE"
}
