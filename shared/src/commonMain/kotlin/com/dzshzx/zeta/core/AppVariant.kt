package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.Strings

/**
 * 正式版（`com.dzshzx.zeta`，用户日常用的）和测试版（`com.dzshzx.zeta.dev`，开发测试装的）可以同时装在手机上。
 * 两个包里要互相区分的名字都从包名推出来，不写死：组件名、Provider 的 authority、广播 action、 Linux 里 Zeta 自己的目录。
 *
 * 两个包共用同一个 Debian 和 Linux 用户，Claude Code 的会话、登录状态和配置是同一份； Zeta 自己在 Linux
 * 里的状态（后台对话、附件、归档）各放各的目录，互不接管、互不清理。
 */
class AppVariant(val packageName: String) {
    /** 不是正式版的包，就是测试版。 */
    val isDev: Boolean
        get() = packageName != RELEASE_PACKAGE

    /**
     * Linux 里 Zeta 自己的目录名：`~/.cache/<它>`（后台对话 hosts、附件 files）和 `~/.config/<它>`（归档）。 正式版是
     * `zeta`（沿用一直以来的位置，升级后照样接得回原来的后台对话），测试版是 `zeta-dev`。Windows
     * 桌面版的正式版、测试版分别用这两个（windows/internal/host 的 LinuxName）：在同一台远程主机上和手机的同版算同一个包，
     * 可以同时接着同一个托管进程，电脑、手机一起用一个会话（见 zeta_host.sh 开头）。
     */
    val linuxName: String
        get() =
            when {
                !isDev -> "zeta"
                packageName.startsWith("$RELEASE_PACKAGE.") ->
                    "zeta-" + packageName.removePrefix("$RELEASE_PACKAGE.").replace('.', '-')
                else -> packageName.replace(Regex("[^A-Za-z0-9_-]"), "-")
            }

    /** Linux 用户家目录 [home] 下 Zeta 自己的缓存目录。 */
    fun cacheDir(home: String): String = "${home.trimEnd('/')}/.cache/$linuxName"

    /** 本包里类 [className] 的组件名（“包名/完整类名”）。类都在 `com.dzshzx.zeta` 命名空间下，两个包一样。 */
    fun component(className: String): String = "$packageName/$className"

    /** 本包的 ContentProvider authority。 */
    fun authority(name: String): String = "$packageName.$name"

    /** 本包的 Intent action。 */
    fun action(name: String): String = "$packageName.action.$name"

    /** 给人看的名字：「Zeta 正式版」或「Zeta 测试版」。 */
    val label: String
        get() = if (isDev) Strings.coreVariantDev() else Strings.coreVariantRelease()

    /** 电源键接管（xposed/PowerKeyHook.kt）写日志用的标签；本包的诊断页按它找本包模块的记录。 正式版、测试版分开，两个名字互不包含，按子串找时也不会串。 */
    val powerKeyTag: String
        get() = if (isDev) "ZetaDevPowerKey" else "ZetaPowerKey"

    /** 放行绑定系统应用工具服务（xposed/AppToolBindHook.kt）写日志用的标签，同样按包分开、互不包含。 */
    val appToolsTag: String
        get() = if (isDev) "ZetaDevAppTools" else "ZetaAppTools"

    /** 放行本包的超级岛焦点通知（xposed/FocusAuthHook.kt）写日志用的标签，同样按包分开、互不包含。 */
    val islandTag: String
        get() = if (isDev) "ZetaDevIsland" else "ZetaIsland"

    /** 通知标题：测试版前面注明，免得和正式版的通知混在一起。 */
    fun notificationTitle(title: String): String =
        if (isDev) Strings.coreVariantNotificationTitle(title) else title

    companion object {
        const val RELEASE_PACKAGE = "com.dzshzx.zeta"

        /** 交给 Linux 一侧（zeta_host.sh、zeta-helper）的环境变量，值是 [linuxName]。 */
        const val LINUX_NAME_ENV = "ZETA_NAME"

        /**
         * 正在运行的这个包。包名在构建时就定了（手机上是 build type 的 applicationIdSuffix），由各平台的 [AppPackage] 在
         * META-INF/services 里登记，不用等 Context。
         */
        val current: AppVariant by lazy { AppVariant(AppPackage.current.packageName) }

        /** [packageName] 是不是 Zeta 的某个包（正式版或测试版）。 */
        fun isZetaPackage(packageName: String): Boolean =
            packageName == RELEASE_PACKAGE || packageName.startsWith("$RELEASE_PACKAGE.")
    }
}

/** 正在运行的这个包，由各平台在 META-INF/services 里登记（见 [current]）。 */
interface AppPackage {
    /** 包名：APK 的 applicationId。 */
    val packageName: String

    /** 本应用所在的 Android 用户的编号（[LinuxEnv.androidUser]）。 */
    val androidUser: Int
        get() = 0

    /** 给人看的应用名（侧栏顶部）：和桌面图标的名字一样，测试版是「Zeta 测试」。 */
    val appName: String
        get() = "Zeta"

    companion object {
        val current: AppPackage by lazy {
            platformService<AppPackage>()
        }
    }
}
