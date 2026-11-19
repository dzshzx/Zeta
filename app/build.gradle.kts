import java.time.Duration

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
        minSdk = 33
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
        release {
            isMinifyEnabled = false
        }
        // 测试版：包名加 .dev，和用户日常用的正式版（debug）并存在手机上，开发测试只装它（tools/install.sh --dev）。
        // 代码、签名同 debug；图标在 src/dev/res 里换掉，名字用 resValue 给。assembleDebug 照旧只出正式版。
        create("dev") {
            initWith(getByName("debug"))
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
        resValues = true  // 测试版各份的桌面名用 resValue 给
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans")
    }

    lint {
        checkReleaseBuilds = false
    }

    // JVM 单元测试里碰到 android.* 的调用返回默认值，不抛 “not mocked”；要真 Android 行为的放到手机上测。
    // 截图测试（app/src/test/kotlin/com/dzshzx/zeta/shots）跑在 Robolectric 里，要用到应用的资源（字体、字符串）。
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric 的 Android 系统镜像（android-all，约 250 MB）交给 Gradle 下载，测试进程直接从 Gradle 缓存里离线取用：
// 测试进程不走 Gradle 的代理和镜像设置，自己去 Maven Central 下载在本机和手机上都不可靠。
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }
val shotsDir = layout.buildDirectory.dir("shots").get().asFile.absolutePath
val noRootPath = file("src/test/no-root").absolutePath + File.pathSeparator + providers.environmentVariable("PATH").getOrElse("")

tasks.withType<Test>().configureEach {
    // 有的测试在临时 HOME 上真跑 assets 里的脚本（zeta_helper.py、zeta_host.sh 等）：只改脚本时也要重跑，不能当成已是最新
    inputs.dir("src/main/assets").withPropertyName("assetScripts").withPathSensitivity(PathSensitivity.RELATIVE)
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
    // 长按电源键交给 Zeta 的 LSPosed 模块（xposed/）：接口由 LSPosed 在 system_server 里提供
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
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
