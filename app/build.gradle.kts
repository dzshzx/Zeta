import java.time.Duration
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.licensee)
}

// 版本号（versionCode 是提交数，versionName 从 git tag 推出）在根目录的 build.gradle.kts 里算
val commitCount: Int by rootProject.extra
val versionLabel: String by rootProject.extra

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
        // 应用内更新的调试覆盖（清单地址、信任的证书，tools/vd prefs-set 设）只在测试版里打开；正式版里这是常量 false，
        // 读覆盖的分支连同设置键一起被 R8 去掉
        buildConfigField("boolean", "UPDATE_DEV_OVERRIDE", "false")
        buildConfigField("String", "APP_NAME", "\"Zeta\"")
    }

    signingConfigs {
        // 测试版（debug）用调试密钥签名。工作站和手机 Debian 上是 ~/.android/zeta-debug.keystore：手机上装着的测试版
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
        // debug 就是测试版：包名加 .dev，和正式版并存在手机上，开发测试只装它（tools/install.sh 装的就是它）；
        // 门禁和单元测试也用它，装机的和测过的是同一份构建。不经 R8，图标在 src/debug/res 里换掉，名字用 resValue 给。
        // 环境变量 ZETA_R8=1 时改照正式版那样经 R8、不可调试（可调试的包 R8 会少做优化，就不像正式版了），用来在测试版上
        // 验 R8 去掉代码以后还能不能跑：发版前跑一次 ZETA_R8=1 tools/phone-run tools/phone-tests/smoke.sh（见
        // docs/release.md）
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Zeta 测试")
            buildConfigField("String", "APP_NAME", "\"Zeta 测试\"")
            resValue("string", "capture_tile", "截屏给 Zeta 测试")
            buildConfigField("boolean", "UPDATE_DEV_OVERRIDE", "true")
            if (providers.environmentVariable("ZETA_R8").isPresent) {
                isDebuggable = false
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
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

    // 原生库安装时解到 nativeLibraryDir（AGP 默认不解、留在 APK 里直接映射）：zeta-ssh 是应用要执行的程序，得是一个文件。
    // 它已经去掉了调试信息，不再交给 NDK 的 strip
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libzeta_ssh.so", "**/libzeta_ssh_x86_64.so")
        }
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
 * 编一个 Go 程序：按 [targets] 各编一份，不用 cgo、去掉调试信息和构建号，同样的源码每次编出一样的字节（应用按内容哈希判断装没装，见
 * core/HelperProgram.kt）。在 go.mod 所在的 [module] 里编 [pkg]。在 Linux 一侧跑的（zeta-helper、claude-switch）编
 * linux 的 arm64、amd64 两份，输出 <outputDir>/<程序名>/linux-<架构>，作为 assets 打进包里，单元测试直接跑本机架构的那一份； 应用自己起的
 * zeta-ssh 编 android/arm64，当原生库打进包里，单元测试另编一份本机的。要有 PATH 上的 go。
 */
@CacheableTask
abstract class GoProgramTask : DefaultTask() {
    /** 编哪几份：GOOS/GOARCH → 输出文件（相对 [outputDir]）。 */
    @get:Input abstract val targets: MapProperty<String, String>

    /** 交给 go build 的包。 */
    @get:Input abstract val pkg: Property<String>

    /** go.mod 所在的目录，在这里编；决定编出什么的文件另由 [sources] 声明。 */
    @get:Internal abstract val module: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /** 编进程序的变量（-ldflags -X），比如 zeta-helper 的构建序号 main.build。 */
    @get:Input abstract val linkVars: MapProperty<String, String>

    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for ((target, path) in targets.get()) {
            val (os, arch) = target.split("/")
            exec.exec {
                workingDir = module.get().asFile
                environment("CGO_ENABLED", "0")
                environment("GOOS", os)
                environment("GOARCH", arch)
                commandLine(
                    "go",
                    "build",
                    "-trimpath",
                    "-buildvcs=false",
                    "-ldflags=" +
                        (listOf("-s", "-w", "-buildid=") +
                                linkVars.get().map { (name, value) -> "-X=$name=$value" })
                            .joinToString(" "),
                    "-o",
                    File(out, path).path,
                    pkg.get(),
                )
            }
        }
    }
}

val helperModule: Directory = rootProject.layout.projectDirectory.dir("helper")

/** 托管脚本 zeta_host.sh：放在 helper/host，zeta-helper 编进程序里；跑托管脚本的测试直接读它。 */
val hostScriptFile: RegularFile = helperModule.file("host/zeta_host.sh")

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
                linkVars.put("main.build", rootProject.extra["helperBuild"].toString())
            },
        "claude-switch" to
            tasks.register<GoProgramTask>("claudeSwitch") {
                pkg.set("github.com/dzshzx/claude-switch")
                sources.from(helperModule.file("go.mod"), helperModule.file("go.sum"))
            },
    )

goPrograms.forEach { (name, task) ->
    task.configure {
        targets.set(listOf("arm64", "amd64").associate { "linux/$it" to "$name/linux-$it" })
        module.set(helperModule)
        outputDir.set(layout.buildDirectory.dir("generated/$name"))
        linkVars.convention(emptyMap())
    }
}

val clientModule: Directory = rootProject.layout.projectDirectory.dir("client")

/**
 * 直连远程主机的 zeta-ssh（仓根 client/cmd/zeta-ssh，见 core/ssh/GoSshLinks.kt）：应用自己起它，所以编成 android/arm64、当原生库
 * lib/arm64-v8a/libzeta_ssh.so 打进包里，装好后在 nativeLibraryDir 里能执行（应用自己的数据目录不许执行程序）。 zetaSshHost
 * 编一份这台机器的，给单元测试起。源码是 client/ 模块，testdata 是指向 helper/ 的链接，不算。
 */
fun GoProgramTask.configureZetaSsh(target: String, path: String, dir: String) {
    pkg.set("./cmd/zeta-ssh")
    module.set(clientModule)
    sources.from(fileTree(clientModule) { exclude("testdata/**") })
    targets.put(target, path)
    outputDir.set(layout.buildDirectory.dir("generated/$dir"))
    linkVars.set(emptyMap())
}

val zetaSsh =
    tasks.register<GoProgramTask>("zetaSsh") {
        // 文件名同 GoSshLinks.LIBRARY；要是 lib*.so，安装时才会被解出来
        configureZetaSsh("android/arm64", "arm64-v8a/libzeta_ssh.so", "zeta-ssh")
    }

// 测试版另带一份 x86_64 的，只给 x86_64 模拟器用（为什么、什么时候删见 GoSshLinks.EMULATOR_LIBRARY，文件名与它相同）：
// linux/amd64 而不是 android/amd64，后者 Go 要求开 cgo；放 arm64-v8a/ 下是为了不让安装器把整个应用换成 x86_64。
// 只接进 debug 变体，正式版的核对在下面 mergeReleaseNativeLibs 上。
val zetaSshEmulatorLibrary = "libzeta_ssh_x86_64.so"
val zetaSshEmulator =
    tasks.register<GoProgramTask>("zetaSshEmulator") {
        configureZetaSsh("linux/amd64", "arm64-v8a/$zetaSshEmulatorLibrary", "zeta-ssh-emulator")
    }

// 正式版的原生库里要有 zeta-ssh、不能有模拟器那份：免得哪天把 zetaSshEmulator 误接进所有变体，带进正式版
tasks
    .matching { it.name == "mergeReleaseNativeLibs" }
    .configureEach {
        // 配置缓存不收引用脚本对象的 doLast：用到的值先取到局部变量里
        val merged = outputs.files
        val emulator = zetaSshEmulatorLibrary
        doLast {
            val names = merged.asFileTree.files.map { it.name }.toSet()
            check("libzeta_ssh.so" in names) {
                "正式版的原生库里没有 libzeta_ssh.so：核对看错了地方，或者 zeta-ssh 没打进去（$names）"
            }
            check(emulator !in names) { "正式版的原生库里有 $emulator：它只给测试版用（见 zetaSshEmulator）" }
        }
    }

val zetaSshHost =
    tasks.register<GoProgramTask>("zetaSshHost") {
        val arch =
            if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "amd64"
        configureZetaSsh("linux/$arch", "zeta-ssh", "zeta-ssh-host")
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
    // 有的测试在临时 HOME 上真跑 assets 里的脚本和托管脚本 zeta_host.sh：只改脚本时也要重跑，不能当成已是最新
    inputs
        .dir("src/main/assets")
        .withPropertyName("assetScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .file(hostScriptFile)
        .withPropertyName("hostScript")
        .withPathSensitivity(PathSensitivity.NONE)
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
    // zeta-ssh：直连的测试（GoSshLinksTest）起这台机器的那一份，路径经 zeta.programs.zeta-ssh 传进去
    val zetaSshDir = zetaSshHost.flatMap { it.outputDir }
    inputs
        .dir(zetaSshDir)
        .withPropertyName("zetaSshHost")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dzeta.programs.zeta-ssh=" + zetaSshDir.get().asFile.resolve("zeta-ssh").path)
        },
    )
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
    // 测试进程跑完一次测试就退出，按短命进程配：只用 C1 编译（省下 C2 的编译内存和一半代码缓存），用 Serial GC
    // （省下 G1 约 70M 的记账结构），malloc 分配区限到 2 个（glibc 默认按核数开，Robolectric 原生图形库的本地内存
    // 因此碎得厉害）。实测每个进程峰值从约 1.7G 降到约 1.1G，测试阶段没变慢
    jvmArgs("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC")
    environment("MALLOC_ARENA_MAX", "2")
    // 合入门禁（scripts/check.sh，没设 ZETA_GATE_FULL=1 时）传 -Pzeta.gate=fast：不跑截图测试（shots 包里类名以 Shots 结尾的）。
    // 它们占单元测试一半的时间，改由全量门禁（每晚的 CI、发版前）跑。不传时全跑。
    // 别的测试不往合入门禁之外挪，除非两条都成立：它慢不是在白等（连接超时、宽限期、等一个不会来的事件，是就改掉等待）；
    // 全量门禁所在的 CI 上它真的跑（钉住真 claude 命令行行为的各 ContractTest 在没有 claude 命令的机器上是跳过的，挪出去就没有地方跑）
    val gate = providers.gradleProperty("zeta.gate").orNull
    require(gate == null || gate == "fast") { "zeta.gate 只认 fast（现在是 $gate）" }
    if (gate == "fast") filter { excludeTestsMatching("com.dzshzx.zeta.shots.*Shots") }
    // 截图测试（app/src/test/kotlin/com/dzshzx/zeta/shots）每次都把图写到 app/build/shots（不进 Git），全量门禁因此也真的画一遍，
    // 渲染崩溃会让测试失败。同时和上一次的同名图比：变了就在旁边多写一张 <名字>_compare.png
    systemProperty("roborazzi.test.record", "true")
    systemProperty("roborazzi.test.compare", "true")
    systemProperty("roborazzi.compare.output.dir", shotsDir)
    systemProperty("zeta.shots.dir", shotsDir)
    // Zeta 经 su 执行的命令在单元测试里一律失败，碰不到开发机或手机 Debian 自己的 su
    environment("PATH", noRootPath)
    // 测试结果不进构建缓存（编译结果照常进）。版本号随提交变、写进 BuildConfig 和清单，换个提交测试的输入就跟着变，
    // 能命中的只有同一个提交在别的树里重跑；只改文档和设备脚本的提交，门禁已经不跑测试（scripts/check.sh）。
    // 要缓存还得先声明一串本机输入：截图目录 app/build/shots，契约测试用的 claude 命令行，bash、jq、ssh 等系统命令，
    // ZETA_TEST_SSH 指的手机 Debian
    outputs.cacheIf("版本号随提交变，换个提交就不命中；截图目录和本机的 claude、jq 等命令行都没声明") { false }
    // 卡死的测试（比如截图测试里在长页面上 performScrollTo）到点就失败，不把门禁和构建名额一直挂着。
    // 不用 JUnit 的单用例超时：它把测试挪到别的线程跑，Robolectric 要求在主线程上
    timeout.set(Duration.ofMinutes(10))
    // 按测试类分到几个进程并行跑：串行时全量要 4 分钟，大头是每例约 1 秒的截图测试。
    // 每个进程峰值约 1.1G，算在单个构建的内存峰值里（tools/gradle 按这个峰值定同时构建数）
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
}

androidComponents {
    onVariants { variant ->
        goPrograms.values.forEach {
            variant.sources.assets?.addGeneratedSourceDirectory(it, GoProgramTask::outputDir)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(zetaSsh, GoProgramTask::outputDir)
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            zetaSshEmulator,
            GoProgramTask::outputDir,
        )
    }
    // 正式版的文件名就是 Release 里的资产名 Zeta-v<版本名>.apk（只有 arm64 一个包）
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Zeta-v$it.apk" })
        }
    }
}

dependencies {
    implementation(project(":shared"))
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
    // Zeta 自带的 LSPosed 模块（xposed/：长按电源键、放行系统应用的工具服务）：接口由 LSPosed 在 system_server 里提供
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.apache.sshd.core)
    // 测试里的 SSH 服务器（Apache MINA SSHD）经 BouncyCastle 认 Ed25519 的主机密钥和客户端公钥；应用本身不再带它
    testImplementation(libs.bcprov)
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
    allowUrl("https://github.com/jordond/materialkolor/blob/master/LICENSE") { because("MIT") }
    bundleAndroidAsset = true
    androidAssetReportPath = "licenses/artifacts.json"
}

/**
 * 把仓里别处的一个文件拷进 assets 的 [target]，仓里只放一份：
 * - zetaLicense：仓根的 LICENSE（GPL-3.0 全文）拷成 licenses/GPL-3.0-only.txt，设置「许可证」页显示它。
 */
@CacheableTask
abstract class AssetFileTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val source: RegularFileProperty

    /** 在 assets 里的路径。 */
    @get:Input abstract val target: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        source.get().asFile.copyTo(File(out, target.get()))
    }
}

val assetFiles: List<TaskProvider<AssetFileTask>> =
    listOf(
        tasks.register<AssetFileTask>("zetaLicense") {
            source.set(rootProject.layout.projectDirectory.file("LICENSE"))
            target.set("licenses/GPL-3.0-only.txt")
            outputDir.set(layout.buildDirectory.dir("generated/zeta-license"))
        },
    )

androidComponents {
    onVariants { variant ->
        assetFiles.forEach {
            variant.sources.assets?.addGeneratedSourceDirectory(it, AssetFileTask::outputDir)
        }
    }
}
