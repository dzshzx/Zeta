// 对话与中枢、设置、远程主机和 SSH、共用界面（见 CLAUDE.md「模块」）：Kotlin Multiplatform 模块，只有 Android 一个目标，
// 跑在 JVM 上，所以 commonMain 里能直接用 JDK 和 Java 库；要 Android 接口的放 androidMain。
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
            api(libs.androidx.navigationevent)
            api(libs.jetbrains.lifecycle.runtime.compose)
        }
    }
}
