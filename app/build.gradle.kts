import java.time.Duration
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.licensee)
}

// 版本号不手写，照 SemVer 和 setuptools-scm 的通行写法从 git 推出来（见 CLAUDE.md「发版与上线」）：
// - versionCode 是 HEAD 的提交数：每合入一次自动加一；同一个提交编出的测试版和正式版相同，一对就知道是哪个提交。
// - 发版时打的 tag（v0.7.1）上，版本名就是 0.7.1（release.yml 构建前先在检出里打上这个 tag，见 docs/release.md）。
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

val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val versionLabel = run {
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

// 正式发版证书：release/signing-certs.txt 的最后一行（SHA-256，小写十六进制，见 docs/release.md「签名」）。
// 应用内检查更新只在本包是这张证书签的时候才查（core/update/UpdateTrust.kt），测试版和别人自己编的包不查
val releaseCert: String =
    providers
        .fileContents(rootProject.layout.projectDirectory.file("release/signing-certs.txt"))
        .asText
        .get()
        .lines()
        .map { it.trim() }
        .last { it.isNotEmpty() && !it.startsWith("#") }
        .also {
            require(Regex("[0-9a-f]{64}").matches(it)) {
                "release/signing-certs.txt 的最后一行不是证书的 SHA-256：$it"
            }
        }

android {
    namespace = "com.dzshzx.zeta"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 2
            }
    }

    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.dzshzx.zeta"
        minSdk = 35
        targetSdk = 37
        versionCode = commitCount
        versionName = versionLabel
        ndk {
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCert\"")
        // 应用内更新的调试覆盖（清单地址、信任的证书，tools/vd.py prefs-set 设）只在测试版里打开；正式版里这是常量 false，
        // 读覆盖的分支连同设置键一起被 R8 去掉
        buildConfigField("boolean", "UPDATE_DEV_OVERRIDE", "false")
    }

    signingConfigs {
        // 测试版和 debug 包用调试密钥签名。工作站和手机 Debian 上是 ~/.android/zeta-debug.keystore：手机上装着的测试版
        // 是它签的，签名一致 pm install -r 才能覆盖安装，否则只能卸载重装、丢掉测试版的全部设置。
        // CI 上没有这个文件（它不进 CI），这时不改 storeFile，用 AGP 自己生成的默认调试密钥：门禁只构建、不装机。
        // 路径按 JVM 的 user.home 取（来自 passwd，不看 HOME），和 AGP 找默认调试密钥的地方一样。
        getByName("debug") {
            val zetaDebugKey =
                file("${System.getProperty("user.home")}/.android/zeta-debug.keystore")
            if (zetaDebugKey.isFile) storeFile = zetaDebugKey
        }
    }

    buildTypes {
        // 正式版（用户日常用的，只在发版时由 .github/workflows/release.yml 构建）：R8 去掉用不到的代码和资源，不可调试。
        // R8 找不到的入口（按类名起的）登记在 proguard-rules.pro。
        // 这里不签名，出的是未签名的 Zeta-v<版本名>.apk：AGP 的签名配置不支持密钥轮换的 lineage，
        // 由 tools/release sign 用正式发版密钥加 release/lineage.bin 只签 APK 签名方案 v3（做法见 docs/release.md）。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // debug：不经 R8，包名同正式版。门禁和单元测试用它，不装到设备上。
        // 测试版：包名加 .dev，和正式版并存在手机上，开发测试只装它（tools/install.sh 默认装的就是它）。
        // 代码、签名同 debug；图标在 src/dev/res 里换掉，名字用 resValue 给。
        // 环境变量 ZETA_R8=1 时改照正式版构建（经 R8、不可调试），用来在测试版上验 R8 去掉代码以后还能不能跑，
        // 例如 ZETA_R8=1 tools/phone-run tools/phone-tests/smoke.sh
        create("dev") {
            initWith(
                getByName(
                    if (providers.environmentVariable("ZETA_R8").isPresent) "release" else "debug",
                ),
            )
            signingConfig = signingConfigs.getByName("debug") // 照正式版构建时也用调试密钥签：正式版自己不签名
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Zeta 测试")
            resValue("string", "capture_tile", "截屏给 Zeta 测试")
            buildConfigField("boolean", "UPDATE_DEV_OVERRIDE", "true")
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        resValues = true // 测试版的桌面名用 resValue 给
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans")
    }

    // 不往包里写 AGP 的依赖信息块（用 Google 的密钥加密的依赖清单，只给 Play 管理中心看）：Zeta 不上架 Play，
    // 别人也解不开、核对不了它
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // JVM 单元测试里碰到 android.* 的调用返回默认值，不抛 “not mocked”；要真 Android 行为的放到手机上测。
    // 截图测试（app/src/test/kotlin/com/dzshzx/zeta/shots）跑在 Robolectric 里，要用到应用的资源（字体、字符串）。
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * 编一个在 Linux 一侧跑的 Go 程序：linux 的 arm64、amd64 各一份，静态链接、去掉调试信息和构建号，同样的源码每次编出一样的字节 （应用按内容哈希判断装没装，见
 * core/HelperProgram.kt）。在 go.mod 所在的 [module] 里编 [pkg]，输出 <outputDir>/<program>/linux-<架构>， 作为
 * assets 打进包里；单元测试直接跑本机架构的那一份。要有 PATH 上的 go。
 */
@CacheableTask
abstract class GoProgramTask : DefaultTask() {
    /** 程序名：assets 里的目录名，也是装好的文件名的前缀（[com.dzshzx.zeta.core.HelperProgram.name]）。 */
    @get:Input abstract val program: Property<String>

    /** 交给 go build 的包。 */
    @get:Input abstract val pkg: Property<String>

    /** go.mod 所在的目录，在这里编；决定编出什么的文件另由 [sources] 声明。 */
    @get:Internal abstract val module: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (arch in listOf("arm64", "amd64")) {
            exec.exec {
                workingDir = module.get().asFile
                environment("CGO_ENABLED", "0")
                environment("GOOS", "linux")
                environment("GOARCH", arch)
                commandLine(
                    "go",
                    "build",
                    "-trimpath",
                    "-buildvcs=false",
                    "-ldflags=-s -w -buildid=",
                    "-o",
                    File(out, "${program.get()}/linux-$arch").path,
                    pkg.get(),
                )
            }
        }
    }
}

val helperModule: Directory = rootProject.layout.projectDirectory.dir("helper")

/**
 * APK 里带的 Linux 程序，程序名 → 编它的任务：
 * - zeta-helper：仓根 helper/ 本身，Zeta 在 Linux 一侧读写会话记录用。
 * - claude-switch：helper/go.mod 里 tool 一行的模块（github.com/dzshzx/claude-switch），账号页用它管多个 Claude 账号。
 *   版本由 go.mod、go.sum 定，源码按 go.sum 的哈希从模块缓存取，所以只看这两个文件。
 */
val goPrograms: Map<String, TaskProvider<GoProgramTask>> =
    mapOf(
        "zeta-helper" to
            tasks.register<GoProgramTask>("zetaHelper") {
                pkg.set(".")
                sources.from(helperModule)
            },
        "claude-switch" to
            tasks.register<GoProgramTask>("claudeSwitch") {
                pkg.set("github.com/dzshzx/claude-switch")
                sources.from(helperModule.file("go.mod"), helperModule.file("go.sum"))
            },
    )

goPrograms.forEach { (name, task) ->
    task.configure {
        program.set(name)
        module.set(helperModule)
        outputDir.set(layout.buildDirectory.dir("generated/$name"))
    }
}

// Robolectric 的 Android 系统镜像（android-all，约 250 MB）交给 Gradle 下载，测试进程直接从 Gradle 缓存里离线取用：
// 测试进程不走 Gradle 的代理和镜像设置，自己去 Maven Central 下载在本机和手机上都不可靠。
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
val shotsDir = layout.buildDirectory.dir("shots").get().asFile.absolutePath
val noRootPath =
    file("src/test/no-root").absolutePath +
        File.pathSeparator +
        providers.environmentVariable("PATH").getOrElse("")

tasks.withType<Test>().configureEach {
    // 有的测试在临时 HOME 上真跑 assets 里的脚本（zeta_host.sh 等）：只改脚本时也要重跑，不能当成已是最新
    inputs
        .dir("src/main/assets")
        .withPropertyName("assetScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // zeta-helper、claude-switch 也一样：测试在临时 HOME 上直接跑本机架构的那一份（[com.dzshzx.zeta.core.TestHelper]），
    // 目录经 zeta.programs.<程序名> 传进去
    goPrograms.forEach { (name, task) ->
        val dir = task.flatMap { it.outputDir }
        inputs.dir(dir).withPropertyName(task.name).withPathSensitivity(PathSensitivity.RELATIVE)
        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf("-Dzeta.programs.$name=" + dir.get().asFile.resolve(name).path)
            },
        )
    }
    // 真机测试用的假 Claude 也一样（FakeClaudeHostTest、FakeClaudeContractTest 直接跑它）
    inputs
        .dir("../tools/fake-claude")
        .withPropertyName("fakeClaude")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val androidAll: FileCollection = robolectricRuntime
    inputs
        .files(androidAll)
        .withPropertyName("robolectricAndroidAll")
        .withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Drobolectric.dependency.dir=" + androidAll.singleFile.parentFile)
        },
    )
    systemProperty("robolectric.offline", "true")
    // Robolectric 的原生图形库经 System.load 载入，JDK 25 不加这一项会在每次测试时打一段警告
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // 截图测试（app/src/test/kotlin/com/dzshzx/zeta/shots）每次都把图写到 app/build/shots（不进 Git），门禁因此也真的画一遍，
    // 渲染崩溃会让测试失败。同时和上一次的同名图比：变了就在旁边多写一张 <名字>_compare.png
    systemProperty("roborazzi.test.record", "true")
    systemProperty("roborazzi.test.compare", "true")
    systemProperty("roborazzi.compare.output.dir", shotsDir)
    systemProperty("zeta.shots.dir", shotsDir)
    // Zeta 经 su 执行的命令在单元测试里一律失败，碰不到开发机或手机 Debian 自己的 su
    environment("PATH", noRootPath)
    // 测试结果不进构建缓存（编译结果照常进）
    outputs.cacheIf("截图写在没声明的 app/build/shots，取回缓存时图不会出来；契约测试依赖本机的 claude 命令行") { false }
    // 卡死的测试（比如截图测试里在长页面上 performScrollTo）到点就失败，不把门禁和构建名额一直挂着。
    // 不用 JUnit 的单用例超时：它把测试挪到别的线程跑，Robolectric 要求在主线程上
    timeout.set(Duration.ofMinutes(10))
    // 按测试类分到几个进程并行跑：串行时全量要 4 分钟，大头是每例约 1 秒的截图测试。
    // 每个进程常驻约 1G，算在单个构建的内存峰值里（tools/gradle 按这个峰值定同时构建数）
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
}

androidComponents {
    onVariants { variant ->
        goPrograms.values.forEach {
            variant.sources.assets?.addGeneratedSourceDirectory(it, GoProgramTask::outputDir)
        }
    }
    // 正式版的文件名就是 Release 里的资产名 Zeta-v<版本名>.apk（只有 arm64 一个包）
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Zeta-v$it.apk" })
        }
    }
}

dependencies {
    implementation(libs.coui2.ui)
    implementation(libs.coui2.preference)
    implementation(libs.coui2.icons)
    implementation(libs.coui2.blur)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent)
    implementation(libs.intellij.markdown)
    implementation(libs.ratex.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.health.connect.client)
    implementation(libs.jsch)
    implementation(libs.bcprov)
    // Zeta 自带的 LSPosed 模块（xposed/：长按电源键、放行系统应用的工具服务）：接口由 LSPosed 在 system_server 里提供
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.apache.sshd.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    robolectricRuntime(libs.robolectric.android.all)
    constraints {
        // 测试代码不直接用 Espresso，ui-test 把它带进来时才抬版本；它自己带上新版以后这条就不起作用了
        testImplementation(libs.androidx.test.espresso.core) {
            because("Espresso 3.5 调的 InputManager.getInstance() 在 SDK 37 的 Robolectric 镜像里没有")
        }
    }
}

// 第三方许可证（见仓根 THIRD_PARTY.md）：licensee 从每个变体的运行时依赖（正式版就是 release 的）生成清单，
// 打进 assets 的 licenses/artifacts.json，设置「开源许可」页照它列。许可证只许下面这些，别的或认不出的构建失败。
// 都和 GPL-3.0 兼容；不在 Maven 上的组件（字体、原生库里的代码、随包的 Go 程序和它们链接的模块）手写在 core/about/ThirdParty.kt。
licensee {
    listOf("Apache-2.0", "MIT", "BSD-3-Clause").forEach { allow(it) }
    // POM 只写了网址、认不出 SPDX 标识的：核对过网址上的全文，because 写它实际是哪种；ThirdParty.LICENSE_URLS 也要加上
    allowUrl("https://github.com/suqi8/coui/blob/main/LICENSE") { because("Apache-2.0") }
    allowUrl("https://www.bouncycastle.org/licence.html") {
        because("MIT：Bouncy Castle 的许可就是 MIT 的原文")
    }
    allowUrl("https://github.com/jordond/materialkolor/blob/master/LICENSE") { because("MIT") }
    // JSch 另列的 JZlib（BSD-3-Clause）、jBCrypt（ISC）两个网址，Maven 当作任选其一，放行第一个就过了
    allowUrl("https://github.com/mwiede/jsch/blob/master/LICENSE.txt") { because("BSD-3-Clause") }
    bundleAndroidAsset = true
    androidAssetReportPath = "licenses/artifacts.json"
}

/** 把仓根的 LICENSE（GPL-3.0 全文）拷成 assets 的 licenses/GPL-3.0-only.txt，设置「许可证」页显示它，仓里不另放一份。 */
@CacheableTask
abstract class LicenseAssetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        license.get().asFile.copyTo(File(out, "licenses/GPL-3.0-only.txt"))
    }
}

val zetaLicense =
    tasks.register<LicenseAssetTask>("zetaLicense") {
        license.set(rootProject.layout.projectDirectory.file("LICENSE"))
        outputDir.set(layout.buildDirectory.dir("generated/zeta-license"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            zetaLicense,
            LicenseAssetTask::outputDir,
        )
    }
}
