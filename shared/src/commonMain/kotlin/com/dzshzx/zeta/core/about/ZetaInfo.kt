package com.dzshzx.zeta.core.about

import com.dzshzx.zeta.i18n.Strings

/** Zeta 自己的版权、授权声明和源码地址：设置「关于」一组和「许可证」页用。 */
object ZetaInfo {
    const val REPO = "https://github.com/dzshzx/Zeta"

    const val COPYRIGHT = "Copyright © 2026 dzshzx"

    /** 非官方声明，按界面语言出字。 */
    val unofficial: String
        get() = Strings.aboutUnofficial()

    /**
     * GPL 要求界面上显示的「适当法律声明」：版权、不提供担保、可以按 GPL 再分发、怎么看许可证全文。 只写第 3 版、不写「或其后续版本」：拷来的 Eta 代码按 GPL-3.0
     * 授权，没有许可后续版本。
     */
    val legalNotice: List<String>
        get() =
            listOf(
                Strings.aboutLegalFree(),
                Strings.aboutLegalWarranty(),
                Strings.aboutLegalEta(),
            )

    /**
     * 这个版本的源码在 GitHub 上的哪个 tag 或提交：正式版（版本名是 0.7.1 这样的）是它的 tag v0.7.1，测试版和别的预发布
     * （0.7.2-dev.3+gabc1234）是版本名里的提交号，认不出时是 null。版本名的写法见 app/build.gradle.kts。
     */
    fun sourceRef(versionName: String): String? =
        when {
            Regex("""\d+\.\d+\.\d+""").matches(versionName) -> "v$versionName"
            else -> Regex("""\+g([0-9a-f]{7,40})$""").find(versionName)?.groupValues?.get(1)
        }

    /** 这个版本的源码：[sourceRef] 那个 tag 或提交的目录，认不出时是仓库首页。 */
    fun sourceUrl(versionName: String): String =
        sourceRef(versionName)?.let { "$REPO/tree/$it" } ?: REPO
}
