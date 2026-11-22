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

// 版本号不手写，照 SemVer 和 setuptools-scm 的通行写法从 git 推出来（见
// CLAUDE.md「发版与上线」）。桌面版（windows/installer/build.sh）照同一算法另算：
// - commitCount（手机版的 versionCode）是 HEAD 的提交数：每合入一次自动加一；同一个提交编出的测试版和正式版相同，一对就知道是哪个提交。
// - 发版时打的 tag（v0.7.1）上，versionLabel 就是 0.7.1（release.yml 构建前先在检出里打上这个 tag，见 docs/release.md）。
// - 别的提交是下一个版本的预发布：0.7.2-dev.3+gabc1234 = 0.7.1 之后第 3 个提交、正往 0.7.2 走、提交号 abc1234。
//   按 SemVer 它排在 0.7.1 之后、0.7.2 之前；+ 后面是构建元数据，不参与比大小。还没有正式版 tag 时以 0.7.0 为目标。
fun git(vararg args: String): String? {
    val out = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    return out.standardOutput.asText.get().trim().takeIf {
        out.result.get().exitValue == 0 && it.isNotEmpty()
    }
}

val commitCount by extra(git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1)

// zeta-helper 的构建序号：helper/ 最后一次提交的时刻（Unix 秒），编进程序（main.build）。主机上同一个包只留一个核心，
// 构建序号大的 serve 让旧核心交班（见 docs/core.md「目标」）；手机版和桌面版从同一个提交编出来的相同。
val helperBuild by extra(git("log", "-1", "--format=%ct", "--", "helper") ?: "0")
val versionLabel by extra {
    val hash = git("rev-parse", "--short=7", "HEAD") ?: "unknown"
    val described =
        git("describe", "--tags", "--long", "--match", "v[0-9]*.[0-9]*.[0-9]*") // v0.7.1-3-gabc1234
    val m = described?.let { Regex("""v(\d+)\.(\d+)\.(\d+)-(\d+)-g[0-9a-f]+""").matchEntire(it) }
    when {
        m == null -> "0.7.0-dev.$commitCount+g$hash"
        m.groupValues[4] == "0" -> "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
        else ->
            "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3].toInt() + 1}-dev.${m.groupValues[4]}+g$hash"
    }
}
