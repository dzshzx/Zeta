package com.dzshzx.zeta.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.ui.AppPage
import com.dzshzx.zeta.ui.PageStack
import com.dzshzx.zeta.ui.setup.RemoteHostScreen
import java.io.File
import javax.swing.SwingUtilities
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 桌面版整个窗口的截图（1280×860），存成 desktop/build/shots/<名字>.png，给人看改动后的样子。不比对旧图，只要画得出来不出错。 中枢和界面都在 Swing
 * 的界面线程上建、画（桌面版的 MainLoop 是它）。
 */
class DesktopShots {
    @get:Rule val tmp = TemporaryFolder()

    private val workstation =
        RemoteHost(
            "r-00000001",
            "工作站",
            "dev@192.0.2.10",
            "/home/dev",
            "/home/dev/project",
            RemoteHost.Via.Direct,
            RemoteHosts.encodeKey(SshKey.generate().blob),
        )

    /** [hosts] 在中枢建出来之前就存进设置（和重开 Zeta 时一样），[pages] 是一开始就开着的页面。 */
    private fun shoot(name: String, hosts: List<RemoteHost> = emptyList(), vararg pages: AppPage) =
        shoot(name, hosts) { hub, platform ->
            DesktopRoot(hub, platform, back = null, pages = PageStack(pages.toList()))
        }

    /** 画 [content]（整个窗口）。 */
    private fun shoot(
        name: String,
        hosts: List<RemoteHost>,
        content: @Composable (ConversationHub, DesktopHubPlatform) -> Unit,
    ) {
        SwingUtilities.invokeAndWait {
            val platform = DesktopHubPlatform(tmp.newFolder())
            platform.settings.remoteHosts = hosts
            val hub = ConversationHub(platform)
            val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) { content(hub, platform) }
            try {
                // 往前走几帧，让进场动画和 LaunchedEffect 跑完
                var time = 0L
                repeat(FRAMES) {
                    scene.render(time)
                    time += FRAME_NANOS
                }
                val image = scene.render(time)
                val out = File("build/shots/$name.png")
                out.parentFile.mkdirs()
                out.writeBytes(checkNotNull(image.encodeToData()).bytes)
            } finally {
                scene.close()
            }
        }
    }

    /** 还没加远程设备：聊天页让人去添加。 */
    @Test fun empty() = shoot("desktop-empty")

    /** 加了一台：侧栏顶上是它，聊天页是新对话。 */
    @Test fun withHost() = shoot("desktop-host", listOf(workstation))

    /** 设置：加过的设备和「添加远程设备」。 */
    @Test fun settings() = shoot("desktop-settings", listOf(workstation), AppPage.Settings)

    /** 添加远程设备：SSH 密钥、从 SSH 配置导入、手动添加。 */
    @Test fun addHost() = shoot("desktop-add-host", emptyList(), AppPage.RemoteHosts)

    /** 一台设备的页面：连接（地址、SSH 密钥、默认工作目录）、检查连接、删除。 */
    @Test
    fun hostPage() =
        shoot("desktop-host-page", listOf(workstation)) { hub, _ ->
            RemoteHostScreen(hub, workstation.id, onBack = {}) {}
        }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 860
        const val FRAMES = 60
        const val FRAME_NANOS = 16_000_000L
    }
}
