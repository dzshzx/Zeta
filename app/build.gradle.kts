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
        versionCode = 23
        versionName = "0.6.15"
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
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans")
    }

    lint {
        checkReleaseBuilds = false
    }

    // JVM 单元测试里碰到 android.* 的调用返回默认值，不抛 “not mocked”；要真 Android 行为的放到手机上测
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
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
}
