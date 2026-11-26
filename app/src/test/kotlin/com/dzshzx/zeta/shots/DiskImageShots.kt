package com.dzshzx.zeta.shots

import com.dzshzx.zeta.core.DiskImageOp
import com.dzshzx.zeta.core.DiskImageStatus
import com.dzshzx.zeta.core.InstallStep
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.ui.setup.DiskImageScreen
import com.dzshzx.zeta.ui.setup.EnvInstallScreen
import org.junit.Test

/** 磁盘镜像转换进行中：页面上的步骤与进度，安装向导里建镜像那一步。状态要 root 才查得到，这里直接摆上去。 */
class DiskImageShots : ShotTest() {
    private val now = System.currentTimeMillis() / 1000

    private fun op(message: String, progress: Pair<Long, Long>?, stepSeconds: Long) =
        DiskImageOp(
            name = "convert",
            phase = "running",
            message = message,
            progress = progress,
            interrupted = false,
            time = now,
            started = now - 222,
            stepStarted = now - stepSeconds,
        )

    private fun status(op: DiskImageOp) =
        DiskImageStatus(
            image = false,
            convertible = true,
            mounted = false,
            sizeBytes = 0,
            allocatedBytes = 0,
            usedBytes = null,
            availableBytes = null,
            oldDirectory = false,
            freeBytes = 120L * DiskImageStatus.GB,
            op = op,
        )

    // 复制到 4.5 GB / 10.1 GB，用了两分钟
    private val copying = op("copy", 4_718_592L to 10_590_617L, stepSeconds = 120)

    /** 页面每次打开都会重新查一遍（没有 root，查出来是空的），查完再把状态摆上去。 */
    @Test
    fun copying() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "disk-image-copy",
            prepare = { hub.environment.image.force("status", status(copying)) },
        ) {
            DiskImageScreen(hub = hub, onBack = {})
        }
    }

    @Test
    fun measuring() {
        val hub = Fixtures.readyHub(app)
        val measuring = op("measure", null, stepSeconds = 48)
        shoot(
            "disk-image-measure",
            prepare = { hub.environment.image.force("status", status(measuring)) },
        ) {
            DiskImageScreen(hub = hub, onBack = {})
        }
    }

    @Test
    fun installing() {
        val hub = Fixtures.readyHub(app)
        val installer = hub.environment.installer
        installer.force("running", true)
        installer.force("step", InstallStep.Image)
        installer.force("done", InstallStep.entries.takeWhile { it != InstallStep.Image }.toSet())
        installer.force("imageOp", copying)
        installer.force("progress", copying.fraction)
        shoot("disk-image-install") { EnvInstallScreen(hub = hub, onBack = {}, onOpenLogin = {}) }
    }
}
