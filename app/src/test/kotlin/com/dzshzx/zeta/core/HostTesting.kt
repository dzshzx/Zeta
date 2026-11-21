package com.dzshzx.zeta.core

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/** zeta_host.sh 测试的共用部分：在临时 HOME 里跑命令、claude 的替身、看进程在不在（限时等一个条件用同包的 waitUntil）。 */
internal object HostTesting {
    /**
     * 以 [home] 作 HOME 跑一条命令，返回标准输出。环境只有 HOME、PATH（[home]/bin 排最前，claude 的替身在那里）、LANG、
     * ZETA_IDLE_STEP=1（看门狗每秒看一次，进程没了它很快退出，不留后台的 sleep）和 [env]。
     * 标准输入立即关掉（应用这头马上断开，起的进程照样在后台跑），标准错误丢掉；一分钟内没结束就失败。
     */
    fun run(home: File, command: List<String>, env: Map<String, String> = emptyMap()): String {
        val process = builder(home, command, env).start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("命令超时：$command", process.waitFor(60, TimeUnit.SECONDS))
        return out
    }

    /** [run] 用的进程（同样的环境，标准错误丢掉），要自己读写标准输入输出、看退出码的测试直接用。 */
    fun builder(
        home: File,
        command: List<String>,
        env: Map<String, String> = emptyMap(),
    ): ProcessBuilder =
        ProcessBuilder(command).directory(home).redirectError(File("/dev/null")).apply {
            environment().apply {
                clear()
                put("HOME", home.path)
                put("PATH", "${home.path}/bin:" + System.getenv("PATH"))
                put("LANG", "C.UTF-8")
                put("ZETA_IDLE_STEP", "1")
                putAll(env)
            }
        }

    /** 以 [name] 作 ZETA_NAME 的环境；null 表示不设（和旧版正式版一样）。 */
    fun named(name: String?): Map<String, String> =
        name?.let { mapOf(AppVariant.LINUX_NAME_ENV to it) }.orEmpty()

    /** 替身的读输入循环：一直读输入管道，和真 Claude Code 一样收到 end_session（zeta_host.sh 结束会话时发的）就退出。 */
    const val READ_UNTIL_END =
        "while read -r line; do case \$line in *'\"subtype\":\"end_session\"'*) exit 0 ;; esac; done"

    /** 卡住的替身：不理 end_session，一直读。 */
    const val READ_FOREVER = "while read -r line; do :; done"

    /** 在 [home]/bin 下写 claude 的替身（命令行里带 claude，助手脚本才认它是活的）：先跑 [prelude]，再跑 [loop]。 */
    fun writeClaudeStandIn(home: File, prelude: String = "", loop: String = READ_UNTIL_END) {
        File(home, "bin/claude").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/bash\n" + (if (prelude.isEmpty()) "" else "$prelude\n") + loop + "\n")
            setExecutable(true)
        }
    }

    fun alive(pid: Int) = File("/proc/$pid").exists()

    /**
     * 替身的 prelude：像 Claude Code 那样用自己的进程号在 ~/.claude/sessions 登记会话 [session]（[registeredPid] 读回来），
     * 输出里先打一行带这个会话的 init（Zeta 认托管的进程时要托管目录输出开头的会话和登记的对得上）。
     */
    fun registersAs(session: String) =
        "mkdir -p \"\$HOME/.claude/sessions\"; " +
            "printf '{\"pid\":%s,\"sessionId\":\"$session\",\"kind\":\"sdk\"}' \$\$ > \"\$HOME/.claude/sessions/\$\$.json\"; " +
            "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"$session\"}'"

    /** 让 [builder] 起的进程身上的记号 ZETA_HOST 是 [mark]；null 时没有（测试本身跑在 Zeta 托管的 Claude Code 里时继承来的也去掉）。 */
    fun marked(builder: ProcessBuilder, mark: String?): ProcessBuilder = builder.apply {
        environment().remove("ZETA_HOST")
        mark?.let { environment()["ZETA_HOST"] = it }
    }

    /** 替身按 [registersAs] 登记的进程号；等它最多 5 秒，没登记就失败。 */
    fun registeredPid(home: File): Int {
        val dir = File(home, ".claude/sessions")
        assertTrue("替身没登记", waitUntil(5) { dir.list().orEmpty().isNotEmpty() })
        return dir.list()!!.single().removeSuffix(".json").toInt()
    }

    /**
     * 测试收尾：结束 [home] 的 ~/.claude/sessions 里登记的、HOME 是 [home] 的进程（包了一层的替身不是 pid 文件记的那个，只能从这里找）。
     * 测试随手登记的假进程号（比如 999999）可能正是别的真进程，HOME 对不上的不动。
     */
    fun killRegistered(home: File) {
        File(home, ".claude/sessions")
            .list()
            .orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .forEach { pid ->
                if (envOf(pid, "HOME") == home.path) {
                    ProcessBuilder("kill", "-9", "$pid").start().waitFor(5, TimeUnit.SECONDS)
                }
            }
    }

    /** 进程启动时的环境变量 [name]；没有、读不了时是 null。 */
    fun envOf(pid: Int, name: String): String? = runCatching {
        File("/proc/$pid/environ").readText()
    }
        .getOrNull()
        ?.split('\u0000')
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

    /**
     * 登录配置怎么把 claude 包一层（见 zeta_host.sh 开头 pid 一项）：[FUNCTION] 是经 BASH_ENV 定义的 shell 函数，[SCRIPT] 是
     * PATH 上排在前面、不 exec 的脚本。
     */
    enum class Wrapper {
        FUNCTION,
        SCRIPT,
    }

    /** 在 [home] 里摆好 [wrapper]，返回跑 zeta_host.sh 时要另加的环境变量。 */
    fun wrapClaude(home: File, wrapper: Wrapper): Map<String, String> =
        when (wrapper) {
            Wrapper.FUNCTION -> {
                val env =
                    File(home, "bash-env.sh").apply {
                        writeText("claude() { command claude \"\$@\"; }\n")
                    }
                mapOf("BASH_ENV" to env.path)
            }
            Wrapper.SCRIPT -> {
                File(home, "wrap/claude").apply {
                    parentFile!!.mkdirs()
                    writeText("#!/bin/bash\n\"\$HOME/bin/claude\" \"\$@\"\n")
                    setExecutable(true)
                }
                mapOf("PATH" to "${home.path}/wrap:${home.path}/bin:" + System.getenv("PATH"))
            }
        }
}
