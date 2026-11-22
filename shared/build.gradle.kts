// 手机和桌面共用的代码：Android 和桌面（JVM）两个目标，都跑在 JVM 上，所以 commonMain 里能直接用 JDK 和 Java 库。
// 只属于一边的放 androidMain、desktopMain。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
    android {
        namespace = "com.dzshzx.zeta.shared"
        compileSdk = 37
        minSdk = 35
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.mp.runtime)
            api(libs.compose.mp.foundation)
            api(libs.coui2.ui.kmp)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.coui2.preference.kmp)
            api(libs.coui2.icons.kmp)
            api(libs.coui2.blur.kmp)
            api(libs.intellij.markdown)
            api(libs.jsch)
            api(libs.bcprov)
            api(libs.androidx.navigationevent)
            api(libs.jetbrains.lifecycle.runtime.compose)
        }
    }
}
