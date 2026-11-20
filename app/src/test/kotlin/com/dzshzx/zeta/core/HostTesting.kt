package com.dzshzx.zeta.core

import java.io.File

/** zeta_host.sh 测试的共用部分：claude 的替身、看进程在不在、限时等一个条件。 */
internal object HostTesting {
    /** 替身的读输入循环：一直读输入管道，和真 Claude Code 一样收到 end_session（zeta_host.sh 结束会话时发的）就退出。 */
    const val READ_UNTIL_END = "while read -r line; do case \$line in *'\"subtype\":\"end_session\"'*) exit 0 ;; esac; done"

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

    /** 每 0.1 秒看一次 [condition]，[seconds] 秒内成立就返回 true。 */
    fun waitUntil(seconds: Int, condition: () -> Boolean): Boolean {
        repeat(seconds * 10) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
