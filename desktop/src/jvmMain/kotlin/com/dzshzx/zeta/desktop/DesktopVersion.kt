package com.dzshzx.zeta.desktop

import java.util.Properties

/**
 * 这个桌面版的版本：[code] 是手机版的 versionCode（构建时 HEAD 的提交数），[name] 是版本名（发版 tag 上是 `0.7.6`，别的提交是
 * `0.7.6-dev.3+gabc1234`），算法见根目录的 build.gradle.kts。构建时写进 jar 的
 * `zeta/version.properties`（desktop/build.gradle.kts 的 versionResource）。
 */
data class DesktopVersion(val code: Int, val name: String) {
    /** 正式发版打的包（版本名是干净的 X.Y.Z）：只有它检查更新，开发构建不查。 */
    val isRelease: Boolean
        get() = code > 0 && Release.matches(name)

    companion object {
        private val Release = Regex("""\d+\.\d+\.\d+""")
        private const val RESOURCE = "/zeta/version.properties"

        /** 读不出来时（资源缺了、写坏了）当开发构建：versionCode 0，不检查更新。 */
        val Unknown = DesktopVersion(0, "未知")

        /** 本进程的版本，从 jar 里读一次。 */
        val current: DesktopVersion by lazy {
            DesktopVersion::class.java.getResourceAsStream(RESOURCE)?.use {
                parse(it.reader(Charsets.UTF_8).readText())
            } ?: Unknown
        }

        /** 读 version.properties 的文字；缺项或不对时是 [Unknown]。 */
        fun parse(text: String): DesktopVersion {
            val props = Properties().apply { load(text.reader()) }
            val code = props.getProperty("versionCode")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val name = props.getProperty("versionName")?.trim()?.takeIf { it.isNotEmpty() }
            return if (code != null && name != null) DesktopVersion(code, name) else Unknown
        }
    }
}
