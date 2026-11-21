package com.dzshzx.zeta.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxEnvTest {
    private val config =
        LinuxEnvConfig(LinuxEnv.OWN_ROOTFS, "dzshzx", 5000, 5000, "/home/dzshzx", "/home/dzshzx")
    private val bind = "mount -o bind ${LinuxEnv.WORKSPACE} \"\$R/workspace\""

    /** /workspace 绑 Zeta 自己的目录：Zeta 进环境的脚本和 SSH 服务的入口（zeta_enter.sh）绑的是同一个，都不碰 Eta 的目录。 */
    @Test
    fun workspaceIsZetasOwnDirectory() {
        val script = LinuxEnv.enterScript(config, "true")
        assertTrue(script, script.contains(bind))
        assertFalse(script, script.contains("/data/local/tmp/eta"))
        val enter = File("src/main/assets/zeta_enter.sh").readText()
        assertTrue(enter, enter.contains(bind))
        assertFalse(enter, enter.contains("/data/local/tmp/eta"))
    }

    /** 工作区在 Debian 的目录外面：换环境、重装环境时删 rootfs 不会带走用户的文件。 */
    @Test
    fun workspaceLivesOutsideTheEnvironment() {
        assertFalse(LinuxEnv.WORKSPACE.startsWith(LinuxEnv.OWN_BASE + "/"))
        assertFalse(LinuxEnv.WORKSPACE.startsWith(LinuxEnv.OWN_ROOTFS + "/"))
    }
}
