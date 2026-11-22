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

compose.desktop { application { mainClass = "com.dzshzx.zeta.desktop.MainKt" } }

// Windows 安装包全在 Linux 上做，本机 runner 就能打（jpackage 只能在 Windows 上跑，所以不用它）：
// windowsApp 把本模块的 jar 和运行时依赖放进一个目录，界面库的原生部分换成 Windows 的；
// packageWindows 再交给 windows/package.sh 配上 Windows 的 JRE、用 wixl 打成 MSI，见那个脚本开头。
val windowsRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    val jvmRuntime = configurations.getByName("jvmRuntimeClasspath")
    extendsFrom(jvmRuntime)
    // 用的是 jvmRuntimeClasspath 的依赖和属性，只把 Linux 的原生库换成 Windows 的
    attributes {
        jvmRuntime.attributes.keySet().forEach {
            @Suppress("UNCHECKED_CAST")
            attribute(it as Attribute<Any>, jvmRuntime.attributes.getAttribute(it)!!)
        }
    }
    exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-linux-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-x64")
}

dependencies { windowsRuntime(compose.desktop.windows_x64) }

val windowsApp by
    tasks.registering(Sync::class) {
        // 不同 group 下有同名的 jar（androidx 和 org.jetbrains.androidx 的 lifecycle），依赖的文件名前都加上 group
        val groups =
            windowsRuntime.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                artifacts.associate {
                    it.file to (it.id.componentIdentifier as? ModuleComponentIdentifier)?.group
                }
            }
        from(tasks.named("jvmJar"))
        from(windowsRuntime) { eachFile { groups.get()[file]?.let { name = "$it.$name" } } }
        into(layout.buildDirectory.dir("windows/app"))
    }

// 版本号和手机版同一份（根目录的 build.gradle.kts）
val versionLabel: String by rootProject.extra

tasks.register<Exec>("packageWindows") {
    group = "distribution"
    description = "打 Windows 安装包 build/windows/Zeta-v<版本>.msi（要 wixl）"
    dependsOn(windowsApp)
    val out = layout.buildDirectory.file("windows/Zeta-v$versionLabel.msi")
    inputs.files(windowsApp)
    inputs.dir(layout.projectDirectory.dir("windows"))
    inputs.dir(layout.projectDirectory.dir("icons"))
    outputs.file(out)
    commandLine(
        layout.projectDirectory.file("windows/package.sh").asFile.path,
        layout.buildDirectory.dir("windows/app").get().asFile.path,
        versionLabel,
        out.get().asFile.path,
    )
}
