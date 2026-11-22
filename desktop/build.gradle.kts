// Zeta 桌面版：Windows 上的窗口程序，经 SSH 连 Linux 主机上的 Claude Code。界面和逻辑大多在 shared/。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(25)
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        jvmTest.dependencies { implementation(libs.junit) }
    }
}

// 在 Linux 一侧跑的东西和手机版是同一份，放进 jar 的 assets/（DesktopHubPlatform.asset 按名字读）：
// 托管脚本、账号脚本从 app 的 assets 取，zeta-helper 和 claude-switch 用 app 里编它们的任务的输出
val app = project(":app")
val linuxAssets =
    files(
        app.layout.projectDirectory.file("src/main/assets/zeta_host.sh"),
        app.layout.projectDirectory.file("src/main/assets/zeta_accounts.sh"),
        app.tasks.named("zetaHelper"),
        app.tasks.named("claudeSwitch"),
    )

tasks.named<ProcessResources>("jvmProcessResources") { from(linuxAssets) { into("assets") } }

compose.desktop {
    application {
        mainClass = "com.dzshzx.zeta.desktop.MainKt"
    }
}
