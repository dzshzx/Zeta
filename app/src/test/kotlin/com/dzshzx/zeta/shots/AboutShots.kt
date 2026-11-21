package com.dzshzx.zeta.shots

import com.dzshzx.zeta.core.about.ThirdParty
import com.dzshzx.zeta.ui.settings.ComponentScreen
import com.dzshzx.zeta.ui.settings.CreditsScreen
import com.dzshzx.zeta.ui.settings.LicenseScreen
import com.dzshzx.zeta.ui.settings.OpenSourceScreen
import org.junit.Test

/** 设置 › 关于下面的几页：许可证、开源许可、组件详情、鸣谢（首页的「关于」一组见 SettingsShots 的 settings-bottom）。 */
class AboutShots : ShotTest() {
    /** 「许可证」：Zeta 的版权和 GPL 要求的声明，下面是 GPL-3.0 全文的开头。 */
    @Test
    fun license() {
        shoot("about-license") { LicenseScreen(onBack = {}) }
    }

    /** 「开源许可」：手写的几条在上，下面是 licensee 生成的依赖库（读的是这次构建打进 assets 的清单）。 */
    @Test
    fun openSource() {
        shoot("about-open-source") { OpenSourceScreen(onBack = {}) }
    }

    /** 一个手写条目的详情：Eta 的版权行和拷来的文件。 */
    @Test
    fun bundledComponent() {
        val eta = ThirdParty.BUNDLED.single { it.name == "Eta" }
        shoot("about-component-eta") { ComponentScreen(eta, onBack = {}) }
    }

    /** 一个 Maven 依赖的详情：JSch 的三份版权行，下面是 BSD-3-Clause 和 ISC 的全文。 */
    @Test
    fun mavenComponent() {
        val jsch = ThirdParty.loadMaven(app).single { "com.github.mwiede:jsch" in it.artifacts }
        shoot("about-component-jsch") { ComponentScreen(jsch, onBack = {}) }
    }

    @Test
    fun credits() {
        shoot("about-credits") { CreditsScreen(onBack = {}) }
    }
}
