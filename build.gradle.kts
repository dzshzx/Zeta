buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 内置 Kotlin 与 Compose 编译插件共用同一版本。
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// 命令 args 的标准输出（去掉首尾空白）；退出码不为 0 或没有输出时是 null。
fun run(vararg args: String): String? {
    val out = providers.exec {
        commandLine(*args)
        isIgnoreExitValue = true
    }
    return out.standardOutput.asText.get().trim().takeIf {
        out.result.get().exitValue == 0 && it.isNotEmpty()
    }
}

fun git(vararg args: String): String? = run("git", *args)

// 版本号不手写，从 git 推出来（见 AGENTS.md「发版与上线」）：算法和写法只在 tools/version.sh，它打印「版本号 版本名 是否正式版」。
// commitCount 是手机版的 versionCode，versionLabel 是 versionName。
val versionFields =
    run("bash", rootDir.resolve("tools/version.sh").path)?.split(" ")
        ?: error("tools/version.sh 没算出版本号")
val commitCount by extra(versionFields[0].toInt())
val versionLabel by extra(versionFields[1])

// zeta-helper 的构建序号：helper/ 最后一次提交的时刻（Unix 秒），编进程序（main.build）。主机上同一个包只留一个核心，
// 构建序号大的 serve 让旧核心交班（见 docs/core.md「目标」）；手机版和桌面版从同一个提交编出来的相同。
val helperBuild by extra(git("log", "-1", "--format=%ct", "--", "helper") ?: "0")
