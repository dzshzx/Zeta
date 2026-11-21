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
}
