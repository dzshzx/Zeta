package com.dzshzx.zeta.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.update.UpdateException
import com.dzshzx.zeta.core.update.UpdateManifest
import com.dzshzx.zeta.core.update.UpdatePackage
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

    /**
     * [hosts] 在中枢建出来之前就存进设置（和重开 Zeta 时一样），[pages] 是一开始就开着的页面。检查更新不联网：[version] 是这个包的版本， 检查更新摆成
     * [update]。
     */
    private fun shoot(
        name: String,
        hosts: List<RemoteHost> = emptyList(),
        vararg pages: AppPage,
        version: DesktopVersion = Release,
        update: DesktopUpdateState = DesktopUpdateState.Idle,
    ) =
        shoot(name, hosts) { hub, platform ->
            val updates = remember { previewUpdates(platform, version, update) }
            DesktopRoot(hub, platform, updates, back = null, pages = PageStack(pages.toList()))
        }

    private fun previewUpdates(
        platform: DesktopHubPlatform,
        version: DesktopVersion,
        state: DesktopUpdateState,
    ) =
        DesktopUpdates(
                settings = platform.settings,
                version = version,
                dir = File(platform.filesDir, "update"),
                feed =
                    object : UpdateFeed {
                        override suspend fun manifest() = throw UpdateException("截图里不联网")

                        override suspend fun download(
                            pkg: UpdatePackage,
                            dest: File,
                            onProgress: (Long) -> Unit,
                        ) = throw UpdateException("截图里不联网")
                    },
                installer = { "截图里不安装" },
                notify = {},
                exit = {},
            )
            .also { it.preview(state) }

    /** 设置页的「关于」，检查更新摆成 [update]。 */
    private fun about(name: String, update: DesktopUpdateState, version: DesktopVersion = Release) =
        shoot(name, listOf(workstation), AppPage.Settings, version = version, update = update)

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

    // 设置「关于」：版本、检查更新的各个结果；有新版本时的下载并安装和更新说明
    @Test
    fun aboutDev() =
        about(
            "desktop-about-dev",
            DesktopUpdateState.Idle,
            DesktopVersion(905, "0.7.6-dev.3+gabc1234"),
        )

    @Test fun aboutChecking() = about("desktop-about-checking", DesktopUpdateState.Checking)

    @Test fun aboutUpToDate() = about("desktop-about-up-to-date", DesktopUpdateState.UpToDate)

    @Test
    fun aboutCheckFailed() =
        about(
            "desktop-about-failed",
            DesktopUpdateState.CheckFailed("连 github.com 超时；连 gh-proxy.com 超时"),
        )

    @Test
    fun updateReady() = about("desktop-update-ready", DesktopUpdateState.Available(NewRelease))

    @Test
    fun updateDownloading() =
        about(
            "desktop-update-downloading",
            DesktopUpdateState.Available(NewRelease, DesktopUpdateStep.Downloading(48_300_000)),
        )

    @Test
    fun updateFailed() =
        about(
            "desktop-update-failed",
            DesktopUpdateState.Available(
                NewRelease,
                DesktopUpdateStep.Failed("从 github.com 下的文件 sha256 和清单不一致，已删掉"),
            ),
        )

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 860
        const val FRAMES = 60
        const val FRAME_NANOS = 16_000_000L

        val Release = DesktopVersion(900, "0.7.5")

        val NewRelease =
            UpdateManifest(
                versionCode = 905,
                versionName = "0.7.6",
                notes =
                    "- feat(desktop): 桌面版自己检查和安装更新\n- fix(chat): 长回答滚动时不再跳动\n- perf(pane): 会话列表打开更快",
                apk = "Zeta-v0.7.6.apk",
                url = "https://github.com/dzshzx/Zeta/releases/download/v0.7.6/Zeta-v0.7.6.apk",
                size = 13_200_000,
                sha256 = "ab".repeat(32),
                publishedAt = "2026-11-22T08:00:00Z",
                msi =
                    UpdatePackage(
                        "Zeta-v0.7.6.msi",
                        "https://github.com/dzshzx/Zeta/releases/download/v0.7.6/Zeta-v0.7.6.msi",
                        115_400_000,
                        "cd".repeat(32),
                    ),
            )
    }
}
