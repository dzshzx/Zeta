package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.LinuxEnv

/** 以 root 读文件失败。[missing]：文件不存在（应用没装、没用过这项功能）。message 是给人看的原因。 */
internal class RootReadError(message: String, val missing: Boolean = false) :
    IllegalStateException(message)

/**
 * 以 root 读别的应用私有目录里的文件：字节原样经 su 的标准输出交回 Zeta 的进程，不经文本、不在别处落盘。
 * 剪贴板历史（[ClipboardHistory]）和聊天图片（[ChatImages]）用它。
 */
internal object RootFiles {
    /** 读 Android 一侧的 [path]，超过 [maxBytes] 不读。读不到时抛出 [RootReadError]。 */
    fun read(path: String, maxBytes: Int, timeoutMs: Long = 20_000): ByteArray {
        val script =
            "[ -e \"\$2\" ] || exit 3; s=\$(/system/bin/stat -c %s \"\$2\") || exit 5; " +
                "[ \"\$s\" -le \"\$3\" ] || { echo \"\$s\" >&2; exit 4; }; exec /system/bin/cat \"\$2\""
        val result =
            LinuxEnv.runRootBytes(
                LinuxEnv.androidScript(script, path, maxBytes.toString()),
                timeoutMs,
            )
        if (result.timedOut) throw RootReadError("读取超时（${timeoutMs / 1000} 秒）：$path")
        val detail = result.stderr.trim().take(200)
        return when (val code = result.exitCode) {
            0 -> result.stdout
            3 -> throw RootReadError("不存在：$path", missing = true)
            4 -> throw RootReadError("文件太大（$detail 字节，上限 $maxBytes）：$path")
            else ->
                throw RootReadError(
                    "读不了（退出码 $code" + (if (detail.isEmpty()) "" else "，$detail") + "）：$path",
                )
        }
    }

    /** 以 root 在 Android 一侧跑 [script]（$1 是 BusyBox，$2 起是 [arguments]），要求输出以 [DONE] 收尾，返回之前的各行。 */
    fun lines(script: String, arguments: List<String>, timeoutMs: Long = 30_000): List<String> {
        val result =
            LinuxEnv.runRoot(
                LinuxEnv.androidScript("$script; echo $DONE", *arguments.toTypedArray()),
                timeoutMs,
            )
        if (result.timedOut) throw IllegalStateException("列目录超时（${timeoutMs / 1000} 秒）")
        val lines = result.stdout.lines()
        val end = lines.indexOfLast { it.trim() == DONE }
        if (end < 0) {
            throw IllegalStateException(
                "以 root 列目录失败（退出码 ${result.exitCode}）" +
                    result.stderr.trim().take(200).let { if (it.isEmpty()) "" else "：$it" },
            )
        }
        return lines.subList(0, end)
    }

    private const val DONE = "ZETA_ROOT_DONE"
}
