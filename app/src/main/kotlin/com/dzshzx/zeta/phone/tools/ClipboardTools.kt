package com.dzshzx.zeta.phone.tools

import android.app.KeyguardManager
import android.content.Context
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/** 剪贴板：读，或者换成给定的文字。经 [ClipboardHelper] 以 shell 身份做，Zeta 在后台也读得到。 */
internal object ClipboardTools : PhoneToolGroup {
    override val tools: List<JsonElement> = listOf(
        tool(
            "clipboard",
            "Read the phone's clipboard, or replace it with text. Works while Zeta and the apps are in the background, but Android refuses reads while the phone is locked. " +
                "To paste into a field afterwards use press_key paste; for plain typing type_text is simpler.",
        ) {
            schema { prop("text", "string", "New clipboard text (empty string clears it). Omit to read the clipboard.") }
        },
    )

    override fun call(context: Context, name: String, input: JsonObject): JsonObject {
        val text = input.str("text")
        if (text != null && text.length > MAX_WRITE) return toolFailure("最多写入 $MAX_WRITE 个字符")
        val out = run(context, if (text == null) "get" else "set", text)
        val line = out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("CLIP_") }
            ?: return toolFailure("剪贴板辅助进程没有回应：" + out.trim().takeLast(300).ifBlank { "没有输出" })
        return when {
            line == ClipboardHelper.DONE -> toolText(if (text!!.isEmpty()) "已清空剪贴板" else "已把 ${text.length} 个字符写入剪贴板")
            // 锁屏时系统读剪贴板一律返回空
            line == ClipboardHelper.EMPTY && context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true ->
                toolFailure("手机锁着屏，系统不让读剪贴板（读出来总是空的）；解锁以后再读")
            line == ClipboardHelper.EMPTY -> toolText("剪贴板是空的")
            line.startsWith(ClipboardHelper.OTHER) -> toolText("剪贴板里不是文字：" + line.removePrefix(ClipboardHelper.OTHER))
            line.startsWith(ClipboardHelper.TEXT) -> {
                val value = runCatching { String(Base64.getDecoder().decode(line.removePrefix(ClipboardHelper.TEXT)), Charsets.UTF_8) }
                    .getOrElse { return toolFailure("剪贴板内容解不开：${it.message}") }
                if (value.length <= MAX_READ) toolText(value)
                else toolText(value.clipChars(MAX_READ) + "\n…（剪贴板共 ${value.length} 个字符，只给了前 $MAX_READ 个）")
            }
            else -> toolFailure("读写剪贴板失败：" + line.removePrefix(ClipboardHelper.ERROR))
        }
    }

    override fun title(name: String, input: JsonObject): String = when (val text = input.str("text")) {
        null -> "读取剪贴板"
        "" -> "清空剪贴板"
        else -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { "写入剪贴板「" + it.clipChars(16, "…") + "」" } ?: "写入剪贴板"
    }

    /** 以 root 起辅助进程，返回它的标准输出和标准错误（结果和失败原因都打在标准输出上）。 */
    private fun run(context: Context, mode: String, stdin: String?): String {
        val result = LinuxEnv.runRoot(appProcess(context.applicationInfo.sourceDir, HELPER, listOf(mode)), 15_000, stdin ?: "")
        return result.stdout + result.stderr
    }

    private val HELPER = ClipboardHelper::class.java.name
    private const val MAX_READ = 20_000
    private const val MAX_WRITE = 100_000
}
