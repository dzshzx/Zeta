import java.time.Duration
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 版本号不手写，照 SemVer 和 setuptools-scm 的通行写法从 git 推出来（见 CLAUDE.md「发版与上线」）：
// - versionCode 是 HEAD 的提交数：每合入一次自动加一；同一个提交编出的测试版和正式版相同，一对就知道是哪个提交。
// - 上线时打的 tag（v0.7.1）上，版本名就是 0.7.1。
// - 别的提交是下一个版本的预发布：0.7.2-dev.3+gabc1234 = 0.7.1 之后第 3 个提交、正往 0.7.2 走、提交号 abc1234。
//   按 SemVer 它排在 0.7.1 之后、0.7.2 之前；+ 后面是构建元数据，不参与比大小。还没有上线 tag 时以 0.7.0 为目标。
fun git(vararg args: String): String? {
    val out = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    return out.standardOutput.asText.get().trim().takeIf { out.result.get().exitValue == 0 && it.isNotEmpty() }
}
val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val versionLabel = run {
    val hash = git("rev-parse", "--short=7", "HEAD") ?: "unknown"
    val described = git("describe", "--tags", "--long", "--match", "v[0-9]*.[0-9]*.[0-9]*") // v0.7.1-3-gabc1234
    val m = described?.let { Regex("""v(\d+)\.(\d+)\.(\d+)-(\d+)-g[0-9a-f]+""").matchEntire(it) }
    when {
        m == null -> "0.7.0-dev.$commitCount+g$hash"
        m.groupValues[4] == "0" -> "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
        else -> "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3].toInt() + 1}-dev.${m.groupValues[4]}+g$hash"
    }
}

android {
    namespace = "com.dzshzx.zeta"
    compileSdk {
        version = release(37) {
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
    }

    signingConfigs {
        // 手机上装着的包用这把调试密钥签名；工作站和手机都放在 ~/.android/zeta-debug.keystore，
        // 签名一致 pm install -r 才能覆盖安装，否则只能卸载重装、丢掉 Zeta 的全部设置。
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/zeta-debug.keystore")
        }
    }

    buildTypes {
        // 正式版（用户日常用的，只在上线时由 tools/install.sh --release 构建安装）：R8 去掉用不到的代码和资源，不可调试。
        // 签名仍用手机上已装的那把调试密钥，换了签名 pm install -r 就装不上。R8 找不到的入口（按类名起的）登记在 proguard-rules.pro。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        // debug：不经 R8，包名同正式版。门禁和单元测试用它，不装到设备上。
        // 测试版：包名加 .dev，和正式版并存在手机上，开发测试只装它（tools/install.sh 默认装的就是它）。
        // 代码、签名同 debug；图标在 src/dev/res 里换掉，名字用 resValue 给。
        // 环境变量 ZETA_R8=1 时改照正式版构建（经 R8、不可调试），用来在测试版上验 R8 去掉代码以后还能不能跑，
        // 例如 ZETA_R8=1 tools/phone-run tools/phone-tests/smoke.sh
        create("dev") {
            initWith(getByName(if (providers.environmentVariable("ZETA_R8").isPresent) "release" else "debug"))
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Zeta 测试")
            resValue("string", "capture_tile", "截屏给 Zeta 测试")
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        resValues = true  // 测试版的桌面名用 resValue 给
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans")
    }

    // JVM 单元测试里碰到 android.* 的调用返回默认值，不抛 “not mocked”；要真 Android 行为的放到手机上测。
    // 截图测试（app/src/test/kotlin/com/dzshzx/zeta/shots）跑在 Robolectric 里，要用到应用的资源（字体、字符串）。
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * 编 zeta-helper（仓根 helper/ 的 Go 程序，Zeta 在 Linux 一侧读写会话记录用）：linux 的 arm64、amd64 各一份，
 * 静态链接、去掉调试信息和构建号，同样的源码每次编出一样的字节（应用按内容哈希判断装没装，见 core/HelperProgram.kt）。
 * 输出 <outputDir>/zeta-helper/linux-<架构>，作为 assets 打进包里；单元测试直接跑本机架构的那一份。要有 PATH 上的 go。
 */
@CacheableTask
abstract class ZetaHelperTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (arch in listOf("arm64", "amd64")) {
            exec.exec {
                workingDir = sources.get().asFile
                environment("CGO_ENABLED", "0")
                environment("GOOS", "linux")
                environment("GOARCH", arch)
                commandLine("go", "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w -buildid=", "-o", File(out, "zeta-helper/linux-$arch").path, ".")
            }
        }
    }
}

val zetaHelper = tasks.register<ZetaHelperTask>("zetaHelper") {
    sources.set(rootProject.layout.projectDirectory.dir("helper"))
    outputDir.set(layout.buildDirectory.dir("generated/zeta-helper"))
}

// Robolectric 的 Android 系统镜像（android-all，约 250 MB）交给 Gradle 下载，测试进程直接从 Gradle 缓存里离线取用：
// 测试进程不走 Gradle 的代理和镜像设置，自己去 Maven Central 下载在本机和手机上都不可靠。
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
val shotsDir = layout.buildDirectory.dir("shots").get().asFile.absolutePath
val noRootPath = file("src/test/no-root").absolutePath + File.pathSeparator + providers.environmentVariable("PATH").getOrElse("")

tasks.withType<Test>().configureEach {
    // 有的测试在临时 HOME 上真跑 assets 里的脚本（zeta_host.sh 等）：只改脚本时也要重跑，不能当成已是最新
    inputs.dir("src/main/assets").withPropertyName("assetScripts").withPathSensitivity(PathSensitivity.RELATIVE)
    // zeta-helper 也一样：测试在临时 HOME 上直接跑本机架构的那一份（[com.dzshzx.zeta.core.TestHelper]）
    val helperDir = zetaHelper.flatMap { it.outputDir }
    inputs.dir(helperDir).withPropertyName("zetaHelper").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dzeta.helper.dir=" + helperDir.get().asFile.resolve("zeta-helper").path) })
    // 真机测试用的假 Claude 也一样（FakeClaudeHostTest、FakeClaudeContractTest 直接跑它）
    inputs.dir("../tools/fake-claude").withPropertyName("fakeClaude").withPathSensitivity(PathSensitivity.RELATIVE)
    val androidAll: FileCollection = robolectricRuntime
    inputs.files(androidAll).withPropertyName("robolectricAndroidAll").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Drobolectric.dependency.dir=" + androidAll.singleFile.parentFile) })
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
    // 每个进程常驻约 1G；tools/gradle 同时最多 3 个构建，最多 4 个进程时内存仍够
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(zetaHelper, ZetaHelperTask::outputDir) }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
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
