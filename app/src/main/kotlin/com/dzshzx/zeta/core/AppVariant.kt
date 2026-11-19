package com.dzshzx.zeta.core

import com.dzshzx.zeta.BuildConfig

/**
 * 正式版（`com.dzshzx.zeta`，用户日常用的）和测试版（`com.dzshzx.zeta.dev`，开发测试装的）可以同时装在手机上。
 * 两个包里要互相区分的名字都从包名推出来，不写死：组件名、Provider 的 authority、广播 action、
 * Linux 里 Zeta 自己的目录。
 *
 * 两个包共用同一个 Debian 和 Linux 用户，Claude Code 的会话、登录状态和配置是同一份；
 * Zeta 自己在 Linux 里的状态（后台对话、附件、归档）各放各的目录，互不接管、互不清理。
 */
class AppVariant(val packageName: String) {
    /** 不是正式版的包，就是测试版。 */
    val isDev: Boolean get() = packageName != RELEASE_PACKAGE

    /**
     * Linux 里 Zeta 自己的目录名：`~/.cache/<它>`（后台对话 hosts、附件 files）和 `~/.config/<它>`（归档）。
     * 正式版是 `zeta`（沿用一直以来的位置，升级后照样接得回原来的后台对话），测试版是 `zeta-dev`。
     */
    val linuxName: String
        get() = when {
            !isDev -> "zeta"
            packageName.startsWith("$RELEASE_PACKAGE.") -> "zeta-" + packageName.removePrefix("$RELEASE_PACKAGE.").replace('.', '-')
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

    /** 通知标题：测试版前面注明，免得和正式版的通知混在一起。 */
    fun notificationTitle(title: String): String = if (isDev) "测试版 · $title" else title

    companion object {
        const val RELEASE_PACKAGE = "com.dzshzx.zeta"

        /** 交给 Debian 里的脚本（zeta_host.sh、zeta_helper.py）的环境变量，值是 [linuxName]。 */
        const val LINUX_NAME_ENV = "ZETA_NAME"

        /** 正在运行的这个包。包名在构建时就定了（build type 的 applicationIdSuffix），不用等 Context。 */
        val current = AppVariant(BuildConfig.APPLICATION_ID)
    }
}
