plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        versionCode = 34
        versionName = "0.6.26"
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
        // 代码、签名同 debug；图标在 src/dev/res 里换掉。assembleDebug 照旧只出正式版。
        // 测试版有三份（.dev、.dev2、.dev3），好让几个任务同时各装各的；装哪一份由 tools/install.sh 按手机租约的空位定，
        // 经 -Pzeta.devSlot=<1..3> 传进来，不传是第一份。
        val devSlot = (findProperty("zeta.devSlot") as String?)?.toIntOrNull()?.takeIf { it in 1..3 } ?: 1
        val devSuffix = if (devSlot == 1) "" else "$devSlot"
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev$devSuffix"
            versionNameSuffix = "-dev$devSuffix"
            resValue("string", "app_name", "Zeta 测试$devSuffix")
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
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.coui)
    implementation(libs.coui.icons)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigationevent)
    implementation(libs.intellij.markdown)
    implementation(libs.ratex.android)
    // Eta 的 COUI 模式同样以 MiuixTheme 承载文字与配色；这里只用它的基础件，不用 Miuix 的界面组件。
    implementation(libs.miuix.ui)
    implementation(libs.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    robolectricRuntime(libs.robolectric.android.all)
}
