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

// 等宽字体也用手机版 res/font 里的那份（MonoFont）
tasks.named<ProcessResources>("jvmProcessResources") {
    from(linuxAssets) { into("assets") }
    from(app.layout.projectDirectory.dir("src/main/res/font")) { into("font") }
}

// 版本号和手机版同一份（根目录的 build.gradle.kts）。MSI 只认 X.Y.Z 三段数字，预发布（0.7.2-dev.3+gabc1234）取前面的 0.7.2
val versionLabel: String by rootProject.extra

compose.desktop {
    application {
        mainClass = "com.dzshzx.zeta.desktop.MainKt"
        // Windows 安装包（MSI）：jpackage 只能在 Windows 上打，由 .github/workflows/desktop.yml 在 GitHub 的
        // Windows 托管机上跑
        // :desktop:packageMsi。装在用户自己的目录下，不要管理员权限；升级靠固定的 upgradeUuid 认出旧版本。
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "Zeta"
            packageVersion = versionLabel.substringBefore('-')
            description = "经 SSH 用远程主机上的 Claude Code"
            vendor = "dzshzx"
            // suggestRuntimeModules 从字节码推出的。不带 jdk.localedata（约 30 MB）：要中文的星期、月份名时自己查表，见
            // core/Quota.kt
            modules(
                "java.instrument",
                "java.naming",
                "java.security.jgss",
                "java.sql",
                "jdk.unsupported",
            )
            windows {
                iconFile.set(layout.projectDirectory.file("icons/zeta.ico"))
                perUserInstall = true
                menu = true
                shortcut = true
                upgradeUuid = "d73fe446-ddad-4182-b6f1-fc4f8ac025fb"
            }
        }
    }
}
