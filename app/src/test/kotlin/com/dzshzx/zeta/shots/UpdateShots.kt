package com.dzshzx.zeta.shots

import com.dzshzx.zeta.core.update.AppUpdates
import com.dzshzx.zeta.core.update.UpdateManifest
import com.dzshzx.zeta.core.update.UpdateState
import com.dzshzx.zeta.core.update.UpdateStep
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.settings.AboutGroup
import com.dzshzx.zeta.ui.settings.UpdateScreen
import org.junit.Test

/**
 * 应用内更新：设置首页「关于」一组里的版本一行（检查更新的几种结果），和「版本更新」页的几步。
 * JVM 上的包不是正式发版证书签的，图上的状态由 [AppUpdates.preview] 直接摆出来，不联网。
 */
class UpdateShots : ShotTest() {
    private val manifest = UpdateManifest(
        versionCode = 905,
        versionName = "0.7.2",
        notes = "- feat(update): 应用内更新：设置「关于」里检查更新，回到前台时每天自动查一次\n" +
            "- fix(chat): 长回答滚到底部时不再跳回上一屏\n" +
            "- perf(helper): 会话列表只读每份记录的头尾",
        apk = "Zeta-v0.7.2.apk",
        url = "https://github.com/dzshzx/Zeta/releases/download/v0.7.2/Zeta-v0.7.2.apk",
        size = 13_240_000,
        sha256 = "0".repeat(64),
        publishedAt = "2026-11-21T08:00:00Z",
    )

    private fun about(name: String, state: UpdateState) {
        AppUpdates.get(app).preview(enabled = true, state = state)
        shoot(name) { CouiPage(title = "设置", onBack = {}) { item { AboutGroup(onOpenSubPage = {}) } } }
    }

    private fun page(name: String, step: UpdateStep) {
        AppUpdates.get(app).preview(enabled = true, state = UpdateState.Available(manifest, step))
        shoot(name) { UpdateScreen(onBack = {}) }
    }

    /** 查过了，已是最新。 */
    @Test
    fun upToDate() = about("about-update-latest", UpdateState.UpToDate)

    /** 有新版本：右边写版本号，点它进「版本更新」。 */
    @Test
    fun available() = about("about-update-available", UpdateState.Available(manifest))

    /** 检查失败：直连和镜像都不通，原因写在版本号下面。 */
    @Test
    fun checkFailed() = about("about-update-check-failed", UpdateState.CheckFailed("连 github.com 超时；连 gh-proxy.com 超时"))

    /** 「版本更新」：还没下载，胶囊是「更新」，下面是更新说明。 */
    @Test
    fun ready() = page("update-ready", UpdateStep.Ready)

    @Test
    fun downloading() = page("update-downloading", UpdateStep.Downloading(done = 5_960_000))

    /** 下好、核对过了，有对话正在回答，等它结束再装。 */
    @Test
    fun waitingIdle() = page("update-waiting", UpdateStep.WaitingIdle)

    /** 核对失败：签名和已装的不一致，文件已删掉，胶囊是「重试」。 */
    @Test
    fun verifyFailed() = page("update-verify-failed", UpdateStep.Failed("核对失败：安装包的签名和已装的 Zeta 不一致，已删掉下载的文件"))
}
