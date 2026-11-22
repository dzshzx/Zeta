package com.dzshzx.zeta.core.diag

import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.HelperProgram
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 各处往运行日志里记事件的入口，热点文件里只加一行调用。消息怎么写在这里定；挑字段、截取的部分是纯函数。 */
object DiagEvents {
    /** 环境检查的结果（ConversationHub.checkSetup）。 */
    fun setupChecked(state: String, rootfs: String?, user: String?) {
        val where = listOfNotNull(rootfs, user).joinToString("，")
        when (state) {
            "Ready" ->
                DiagLog.info(DiagTopic.Env, "Linux 环境可用" + if (where.isEmpty()) "" else "（$where）")
            "NoRoot" -> DiagLog.warn(DiagTopic.Env, "没有 root 权限，进不了 Linux 环境")
            "NoEnvironment" ->
                DiagLog.warn(
                    DiagTopic.Env,
                    "没找到 Linux 环境" + if (rootfs != null) "（$rootfs）" else "",
                )
            else -> DiagLog.info(DiagTopic.Env, "环境检查：$state")
        }
    }

    /** 每条进 Linux 环境的命令跑完后（LinuxEnv.run）：进不去环境、helper 出错的记下来，别的不记。 */
    fun linuxRun(command: String, result: CommandResult) {
        val stderr = tail(result.stderr)
        when {
            result.exitCode == 125 || result.exitCode == 126 ->
                DiagLog.error(
                    DiagTopic.Env,
                    "进不了 Linux 环境（退出码 ${result.exitCode}）" +
                        stderr.let { if (it.isEmpty()) "" else "：$it" },
                )
            else -> {
                val helper = helperCommand(command) ?: return
                if (result.exitCode == 0) return
                val what = if (result.timedOut) "超时" else "出错（退出码 ${result.exitCode}）"
                DiagLog.error(
                    DiagTopic.Helper,
                    "helper $helper $what" + if (stderr.isEmpty()) "" else "：$stderr",
                )
            }
        }
    }

    /** 手机工具调用的结果（PhoneTools.handle）：失败的记下来。 */
    fun phoneTool(name: String, result: JsonObject) {
        val error = toolError(result) ?: return
        DiagLog.warn(DiagTopic.Phone, "手机工具 $name 失败：$error")
    }

    /** 跑 zeta-helper 的命令行（[HelperProgram.command]）里的子命令，装它的那一行是 install；不是在跑它返回 null。 */
    fun helperCommand(command: String): String? {
        if ("/" + HelperProgram.tempPrefix(HelperProgram.ZETA_HELPER) + "." in command)
            return "install"
        if ("/" + HelperProgram.ZETA_HELPER + "-" !in command) return null
        return command.substringAfter(HELPER_EXEC, "").trim().substringBefore(' ').ifEmpty { null }
    }

    /** [HelperProgram.command] 里执行装好的程序的那一段，子命令紧跟其后。 */
    private const val HELPER_EXEC = "exec \"\$f\" "

    /** 工具结果是失败（isError）时取出第一段文字，截短；成功返回 null。 */
    fun toolError(result: JsonObject): String? {
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val text =
            (result["content"] as? JsonArray)
                ?.firstNotNullOfOrNull { block ->
                    (block as? JsonObject)
                        ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
                        ?.get("text")
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                }
                .orEmpty()
        // 读屏失败时前面是整屏控件列表，只要第一行（说明哪里不对）
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return first.take(300).ifEmpty { "（没有说明）" }
    }

    /** 标准错误输出的最后几行（最多 600 字）。 */
    fun tail(stderr: String, lines: Int = 6): String =
        stderr
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(lines)
            .joinToString("\n")
            .takeLast(600)
}
