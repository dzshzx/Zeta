package com.dzshzx.zeta.core.about

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * APK 里的一个第三方组件：设置「开源许可」页的一行，仓根 THIRD_PARTY.md 的一行。 [license] 是 SPDX 表达式（`AND` 是都要遵守，`OR`
 * 是任选其一）；[copyright] 是要随包附上的版权行， MIT、BSD、ISC、OFL、GPL 都要求保留，Apache-2.0 不要求。
 */
@Immutable
internal data class Component(
    val name: String,
    val version: String,
    val license: String,
    val url: String?,
    val copyright: List<String> = emptyList(),
    /** 它是什么、在 APK 的哪里。手写的条目才有。 */
    val note: String? = null,
    /** 从它拷来的、仓里的文件（相对仓根）。 */
    val files: List<String> = emptyList(),
    /** 它里面各自有许可证的部分（原生库静态链接的 crate）。 */
    val parts: List<Part> = emptyList(),
    /** Maven 坐标（group:artifact）：同名、同版本、同许可证的几个构件（Kotlin 多平台的元数据和 Android 构件）并成一项。 */
    val artifacts: List<String> = emptyList(),
)

/** 组件里的一部分，[license] 照上游写的；用的是要附版权行的那种时 [copyright] 写上。 */
@Immutable
internal data class Part(
    val name: String,
    val version: String,
    val license: String,
    val copyright: String? = null,
)

/**
 * 随 APK 分发的第三方组件，两部分：
 * - Maven 依赖（[maven]）：构建时 licensee 从这个变体的运行时依赖生成 assets 的 [REPORT]，许可证不在 app/build.gradle.kts
 *   的允许名单里就构建失败。POM 只写了许可证网址的，按 [LICENSE_URLS] 认成 SPDX 标识；版权行和 POM 写得不准的地方在 [MAVEN_NOTES] 补。
 * - 不在 Maven 上的（[BUNDLED]）：手写，ThirdPartyTest 核对它们和仓里的实际情况一致（go.mod、版本目录、字体、拷来的文件）。 仓根
 *   THIRD_PARTY.md 由 ThirdPartyTest 从这两部分生成，和应用里显示的是同一份。
 */
internal object ThirdParty {
    const val REPORT = "licenses/artifacts.json"

    /**
     * POM 只给了网址、没有 SPDX 标识的许可证：核对过网址上的全文是哪一种。app/build.gradle.kts 的 licensee 一节按网址放行，
     * 新放行一个网址时这里也要加（ThirdPartyTest 拦着）；JSch 列了三个网址，Maven 当作任选其一，licensee 只放行第一个。
     */
    val LICENSE_URLS: Map<String, String> =
        mapOf(
            "https://github.com/suqi8/coui/blob/main/LICENSE" to "Apache-2.0",
            "https://www.bouncycastle.org/licence.html" to "MIT",
            "https://github.com/jordond/materialkolor/blob/master/LICENSE" to "MIT",
            "https://github.com/mwiede/jsch/blob/master/LICENSE.txt" to "BSD-3-Clause",
            "https://github.com/mwiede/jsch/blob/master/LICENSE.JZlib.txt" to "BSD-3-Clause",
            "https://github.com/mwiede/jsch/blob/master/LICENSE.jBCrypt.txt" to "ISC",
        )

    /** Maven 依赖要补的：版权行（取自构件里的许可证文件），以及 POM 写得不对的许可证和上游地址。键是 group:artifact。 */
    val MAVEN_NOTES: Map<String, MavenNote> =
        mapOf(
            // 三个网址分别管 JSch 自己、它带的 JZlib 和 jBCrypt，三份都适用
            "com.github.mwiede:jsch" to
                MavenNote(
                    license = "BSD-3-Clause AND ISC",
                    copyright =
                        listOf(
                            "Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft,Inc.",
                            "JZlib: Copyright (c) 2000-2011 ymnk, JCraft,Inc.",
                            "jBCrypt: Copyright (c) 2006 Damien Miller <djm@mindrot.org>",
                        ),
                ),
            "org.bouncycastle:bcprov-jdk18on" to
                MavenNote(
                    copyright =
                        listOf(
                            "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)",
                        ),
                ),
            "com.materialkolor:material-color-utilities" to
                MavenNote(copyright = listOf("Copyright (c) 2025 Jordon de Hoog")),
            "com.materialkolor:material-color-utilities-android" to
                MavenNote(copyright = listOf("Copyright (c) 2025 Jordon de Hoog")),
            "org.checkerframework:checker-qual" to
                MavenNote(
                    copyright =
                        listOf("Copyright 2004-present by the Checker Framework developers"),
                ),
            // Health Connect 里改了包名的 protobuf
            "androidx.health.connect:connect-client-external-protobuf" to
                MavenNote(copyright = listOf("Copyright 2008 Google Inc.")),
            "io.github.erweixin:ratex-android" to
                MavenNote(copyright = listOf("Copyright (c) erweixin")),
            "com.google.j2objc:j2objc-annotations" to
                MavenNote(url = "https://github.com/google/j2objc"),
        )

    @Immutable
    data class MavenNote(
        val license: String? = null,
        val copyright: List<String> = emptyList(),
        val url: String? = null,
    )

    /** 从 Eta 拷来改写的文件（相对仓根）。主题、状态色和别的界面是照 Eta 的设计重写的，不在这里。 */
    val ETA_FILES: List<String> =
        listOf(
                "ui/markdown/ChatMarkdown.kt",
                "ui/markdown/MarkdownContent.kt",
                "ui/markdown/MarkdownDocumentBuilder.kt",
                "ui/markdown/MarkdownInline.kt",
                "ui/markdown/MarkdownMathContent.kt",
                "ui/markdown/MarkdownMathEngine.kt",
                "ui/markdown/MarkdownMathParser.kt",
                "ui/markdown/MarkdownModel.kt",
                "ui/markdown/MarkdownStyle.kt",
                "ui/markdown/SmoothTextReveal.kt",
                "ui/markdown/StaticMarkdownDocumentCache.kt",
                "ui/markdown/StreamingGfmParser.kt",
                "ui/markdown/StreamingMarkdownRestoreState.kt",
                "ui/markdown/StreamingMarkdownState.kt",
                "ui/markdown/StreamingMarkdownStateCache.kt",
                "ui/markdown/TexMathDelimiters.kt",
                "ui/components/ConversationSidePaneScaffold.kt",
                "ui/components/ConversationPaneState.kt",
                "ui/components/ConversationPaneMotion.kt",
                "ui/layout/AdaptivePageLayout.kt",
                "ui/components/MonoFont.kt",
                "ui/components/coui2/Dialogs.kt",
                "assistant/AssistantPanel.kt",
                "assistant/AssistantSurface.kt",
                "assistant/AssistantComposer.kt",
                "assistant/AssistantSuggestionRow.kt",
                "assistant/AssistantEdgeGlow.kt",
                "assistant/AssistantReply.kt",
            )
            .map { "app/src/main/kotlin/com/dzshzx/zeta/$it" }

    /**
     * RaTeX 的版本（gradle/libs.versions.toml 的 ratex）：下面 KaTeX 字体和 Rust 依赖两条照这个版本核对过，升级 RaTeX 时一起重核。
     */
    const val RATEX_VERSION = "0.1.16"

    /** RaTeX 的 AAR 带进 assets/fonts/ 的字体。 */
    val KATEX_FONTS: List<String> =
        listOf(
                "AMS-Regular",
                "Caligraphic-Bold",
                "Caligraphic-Regular",
                "Fraktur-Bold",
                "Fraktur-Regular",
                "Main-Bold",
                "Main-BoldItalic",
                "Main-Italic",
                "Main-Regular",
                "Math-BoldItalic",
                "Math-Italic",
                "SansSerif-Bold",
                "SansSerif-Italic",
                "SansSerif-Regular",
                "Script-Regular",
                "Size1-Regular",
                "Size2-Regular",
                "Size3-Regular",
                "Size4-Regular",
                "Typewriter-Regular",
            )
            .map { "KaTeX_$it.ttf" }

    /**
     * RaTeX 的原生库（lib/arm64-v8a/libratex_ffi.so）静态链接的 Rust 代码：按 RaTeX 这个版本的 Cargo.lock 从 ratex-ffi
     * 往下数（不算只在编译时用的过程宏和只给 Windows 的），标准库的版本取自库里留下的 rustc 版本号。 几种许可证任选的，用不要求附版权行的那种（Apache-2.0）；只能用
     * MIT 的附上版权行。
     */
    val RATEX_RUST: List<Part> =
        listOf(
            Part(
                "Rust 标准库（core、alloc、std，含 hashbrown、gimli、addr2line、miniz_oxide、rustc-demangle）",
                "1.100.0",
                "MIT OR Apache-2.0",
            ),
            Part("aho-corasick", "1.1.5", "Unlicense OR MIT", "Copyright (c) 2015 Andrew Gallant"),
            Part("bit-set", "0.8.0", "Apache-2.0 OR MIT"),
            Part("bit-vec", "0.8.0", "Apache-2.0 OR MIT"),
            Part("bytes", "1.12.1", "MIT", "Copyright (c) 2018 Carl Lerche"),
            Part("cesu8", "1.1.0", "Apache-2.0 OR MIT"),
            Part("cfg-if", "1.0.5", "MIT OR Apache-2.0"),
            Part("combine", "4.6.9", "MIT", "Copyright (c) 2015 Markus Westerlind"),
            Part("fancy-regex", "0.20.0", "MIT", "Copyright 2015 The Fancy Regex Authors."),
            Part("itoa", "1.0.18", "MIT OR Apache-2.0"),
            Part("jni", "0.21.1", "MIT OR Apache-2.0"),
            Part("jni-sys", "0.3.1, 0.4.1", "MIT OR Apache-2.0"),
            Part("log", "0.4.34", "MIT OR Apache-2.0"),
            Part("memchr", "2.8.3", "Unlicense OR MIT", "Copyright (c) 2015 Andrew Gallant"),
            Part("phf", "0.13.1", "MIT", "Copyright (c) 2014-2022 Steven Fackler, Yuki Okushi"),
            Part(
                "phf_shared",
                "0.13.1",
                "MIT",
                "Copyright (c) 2014-2022 Steven Fackler, Yuki Okushi",
            ),
            Part("regex", "1.13.1", "MIT OR Apache-2.0"),
            Part("regex-automata", "0.4.18", "MIT OR Apache-2.0"),
            Part("regex-lite", "0.1.9", "MIT OR Apache-2.0"),
            Part("regex-syntax", "0.8.11", "MIT OR Apache-2.0"),
            Part("serde", "1.0.229", "MIT OR Apache-2.0"),
            Part("serde_core", "1.0.229", "MIT OR Apache-2.0"),
            Part("serde_json", "1.0.154", "MIT OR Apache-2.0"),
            Part("siphasher", "1.0.4", "MIT OR Apache-2.0"),
            Part("thiserror", "1.0.69", "MIT OR Apache-2.0"),
            Part("tinyvec", "1.13.3", "Zlib OR Apache-2.0 OR MIT"),
            Part("unicode-normalization", "0.1.26", "(MIT OR Apache-2.0) AND Unicode-3.0"),
            Part("zmij", "1.0.23", "MIT", "David Tolnay"),
        )

    /**
     * zeta-helper（仓根 helper/，Go）静态链接的 Go 标准库之外的模块，和 helper/go.mod 的 require 一一对应、版本相同
     * （ThirdPartyTest 核对）。按 go.mod 的 tool 编进 APK 的程序也是 require 里的模块，一样在这里列一条。
     */
    val GO_MODULES: List<Component> =
        listOf(
            Component(
                name = "golang.org/x/text",
                version = "v0.44.0",
                license = "BSD-3-Clause",
                url = "https://go.googlesource.com/text",
                copyright = listOf("Copyright 2009 The Go Authors."),
                note =
                    "Go 团队维护的文字编码库，zeta-helper 读 GB18030 文本时用，静态链接进 assets/zeta-helper/linux-arm64、linux-amd64。",
            ),
            Component(
                name = "github.com/dzshzx/claude-switch",
                version = "v0.0.0-20261121054243-2d865e0a49de",
                license = "MIT",
                url = "https://github.com/dzshzx/claude-switch",
                copyright = listOf("Copyright (c) 2026 dzshzx"),
                note =
                    "给 Claude Code 冷切换多个 Claude 账号的程序，账号页用它。按 helper/go.mod 的 tool 编成单独的程序（未修改），" +
                        "打进 assets/claude-switch/linux-arm64、linux-amd64，第一次用时装进 Linux 一侧。",
            ),
        )

    /** 不在 Maven 依赖里、手写的条目。 */
    val BUNDLED: List<Component> =
        listOf(
            Component(
                name = "Eta",
                version = "2026-11-21",
                license = "GPL-3.0-only",
                url = "https://github.com/Mangi-11/Eta",
                copyright = listOf("Copyright © 2026 蛮吉 (Mangi-11)"),
                note =
                    "Zeta 的 Markdown 渲染（ui/markdown）、侧栏骨架、自适应页面布局、等宽字体的定义、对话框的接口和数字助理的浮窗是从 Eta 拷来再改写的，" +
                        "按 GPL-3.0 使用；所以 Zeta 也按 GPL-3.0 发布。",
                files = ETA_FILES,
            ),
            Component(
                name = "JetBrains Mono NL",
                version = "2.304",
                license = "OFL-1.1",
                url = "https://github.com/JetBrains/JetBrainsMono",
                copyright =
                    listOf(
                        "Copyright 2020 The JetBrains Mono NL Project Authors (https://github.com/JetBrains/JetBrainsMonoNL)",
                    ),
                note = "代码、命令和日志用的等宽字体（无连字版的 Regular、Bold），未修改。",
                files =
                    listOf(
                        "app/src/main/res/font/jetbrains_mono_regular.ttf",
                        "app/src/main/res/font/jetbrains_mono_bold.ttf",
                    ),
            ),
            Component(
                name = "KaTeX 字体",
                version = "随 RaTeX $RATEX_VERSION",
                license = "OFL-1.1",
                url = "https://github.com/KaTeX/KaTeX",
                copyright =
                    listOf(
                        "Copyright (c) 2009-2010, Design Science, Inc. (<www.mathjax.org>)",
                        "Copyright (c) 2014-2018 Khan Academy (<www.khanacademy.org>),",
                        "with Reserved Font Names KaTeX_AMS, KaTeX_Caligraphic, KaTeX_Fraktur, KaTeX_Main, KaTeX_Math, KaTeX_SansSerif, " +
                            "KaTeX_Script, KaTeX_Size1, KaTeX_Size2, KaTeX_Size3, KaTeX_Size4, KaTeX_Typewriter.",
                    ),
                note =
                    "RaTeX $RATEX_VERSION 排公式用的字体，随它的 AAR 打进 assets/fonts/（${KATEX_FONTS.size} 个 KaTeX_*.ttf），未修改。",
            ),
            Component(
                name = "RaTeX 原生库里的 Rust 代码",
                version = "随 RaTeX $RATEX_VERSION",
                license = "Apache-2.0 AND MIT AND Unicode-3.0",
                url = "https://github.com/erweixin/RaTeX/blob/v$RATEX_VERSION/Cargo.lock",
                note =
                    "RaTeX 的原生库 lib/arm64-v8a/libratex_ffi.so 静态链接的 Rust 标准库和 crate（RaTeX 自己的代码见 RaTeX Android 一条）。",
                parts = RATEX_RUST,
            ),
            Component(
                name = "Go 标准库与运行时",
                version = "1.27.4",
                license = "BSD-3-Clause",
                url = "https://go.dev/",
                copyright = listOf("Copyright 2009 The Go Authors."),
                note =
                    "zeta-helper 和 claude-switch 是 Go 程序，各编成 linux 的 arm64、amd64 两份打进 assets/zeta-helper/、assets/claude-switch/，" +
                        "静态链接 Go 标准库和运行时。",
            ),
        ) + GO_MODULES

    private val json = Json { ignoreUnknownKeys = true }

    /** 打进 APK 的 Maven 依赖，按名字排；读不出 licensee 的清单时是空的。 */
    fun loadMaven(context: Context): List<Component> = runCatching {
        context.assets.open(REPORT).bufferedReader().use { maven(it.readText()) }
    }
        .getOrDefault(emptyList())

    /** licensee 的清单（[REPORT]）换成组件：同名、同版本、同许可证的构件并成一项，按名字排。 */
    fun maven(report: String): List<Component> {
        val artifacts = json.decodeFromString<List<LicenseeArtifact>>(report)
        return artifacts
            .map { it to resolve(it) }
            .groupBy { (artifact, resolved) ->
                Triple(artifact.name ?: artifact.artifactId, artifact.version, resolved.license)
            }
            .map { (key, group) ->
                val coordinates = group.map { (artifact, _) ->
                    "${artifact.groupId}:${artifact.artifactId}"
                }
                Component(
                    name = key.first,
                    version = key.second,
                    license = key.third,
                    url = group.firstNotNullOfOrNull { it.second.url },
                    copyright = group.flatMap { it.second.copyright }.distinct(),
                    artifacts = coordinates,
                )
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, Component::name).thenBy { it.version },
            )
    }

    private data class Resolved(val license: String, val copyright: List<String>, val url: String?)

    /** 一个构件的许可证表达式、版权行和上游地址。网址没登记在 [LICENSE_URLS] 的照原样写成 `LicenseRef-<网址>`，测试会拦下。 */
    private fun resolve(artifact: LicenseeArtifact): Resolved {
        val note = MAVEN_NOTES["${artifact.groupId}:${artifact.artifactId}"]
        val ids =
            artifact.spdxLicenses.map { it.identifier } +
                artifact.unknownLicenses.map { license ->
                    license.url?.let { LICENSE_URLS[it] }
                        ?: "LicenseRef-${license.url ?: license.name}"
                }
        return Resolved(
            // POM 里列几个许可证时，Maven 的约定是任选其一
            license = note?.license ?: ids.distinct().joinToString(" OR "),
            copyright = note?.copyright.orEmpty(),
            url = note?.url ?: artifact.scm?.url,
        )
    }

    @Serializable
    private data class LicenseeArtifact(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val name: String? = null,
        val spdxLicenses: List<SpdxLicense> = emptyList(),
        val unknownLicenses: List<UnknownLicense> = emptyList(),
        val scm: Scm? = null,
    )

    @Serializable private data class SpdxLicense(val identifier: String)

    @Serializable
    private data class UnknownLicense(val name: String? = null, val url: String? = null)

    @Serializable private data class Scm(val url: String)
}
