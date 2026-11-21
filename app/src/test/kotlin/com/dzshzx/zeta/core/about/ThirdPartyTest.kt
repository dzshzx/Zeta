package com.dzshzx.zeta.core.about

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第三方清单（[ThirdParty]）和仓里的实际情况一致：licensee 生成的 Maven 清单都认得出许可证、要的版权行都有； 手写的条目对得上
 * helper/go.mod、gradle/libs.versions.toml、字体文件和从 Eta 拷来的文件；每种许可证的全文都在 assets 里； 仓根 THIRD_PARTY.md
 * 和应用里显示的是同一份。升级依赖、加依赖后这里失败时，照失败信息改 ThirdParty.kt 或重新生成 THIRD_PARTY.md。
 *
 * 跑在 Robolectric 里，读的是这个变体合并后的 assets（含构建时生成的 licenses/artifacts.json 和 GPL 全文）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ThirdPartyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val repo = File("..").absoluteFile.normalize()

    private fun asset(path: String): ByteArray = app.assets.open(path).use { it.readBytes() }

    private val maven: List<Component> by lazy {
        ThirdParty.maven(String(asset(ThirdParty.REPORT)))
    }

    private val all: List<Component>
        get() = ThirdParty.BUNDLED + maven

    @Test
    fun mavenLicensesAreRecognised() {
        assertTrue("licensee 的清单是空的", maven.isNotEmpty())
        for (c in maven) {
            assertTrue(
                "${c.artifacts} 的许可证 ${c.license} 没登记：网址加进 ThirdParty.LICENSE_URLS",
                "LicenseRef-" !in c.license,
            )
        }
        val report = String(asset(ThirdParty.REPORT))
        for (url in ThirdParty.LICENSE_URLS.keys) assertTrue(
            "ThirdParty.LICENSE_URLS 的 $url 已经没有依赖用了",
            "\"$url\"" in report,
        )
        val coordinates = maven.flatMap { it.artifacts }.toSet()
        for (key in ThirdParty.MAVEN_NOTES.keys) assertTrue(
            "ThirdParty.MAVEN_NOTES 的 $key 已经不在依赖里了",
            key in coordinates,
        )
    }

    /** 每一项的许可证都在允许名单里（GPL 类的只许点名的组件用），要附版权行的都附了。 */
    @Test
    fun licencesAreAllowedAndNoticesPresent() {
        for (c in all) {
            val allowed = PERMISSIVE + listOfNotNull(NAMED[c.name])
            val usable =
                Licenses.alternatives(c.license).filter { alt -> alt.all { it in allowed } }
            assertTrue("${c.name} 是 ${c.license}，不在允许名单里", usable.isNotEmpty())
            // 由几部分组成的，版权行按部分附（下面核对）
            if (c.parts.isEmpty() && usable.all { alt -> alt.any { it in NEEDS_NOTICE } }) {
                assertTrue(
                    "${c.name} 是 ${c.license}，要附版权行（Maven 依赖写进 ThirdParty.MAVEN_NOTES）",
                    c.copyright.isNotEmpty(),
                )
            }
            val covered = Licenses.ids(c.license).toSet()
            for (part in c.parts) {
                val options =
                    Licenses.alternatives(part.license).filter { alt -> alt.all { it in covered } }
                assertTrue(
                    "${c.name} 里的 ${part.name} 是 ${part.license}，不在 ${c.license} 之内",
                    options.isNotEmpty(),
                )
                if (options.all { alt -> alt.any { it in NEEDS_NOTICE } }) {
                    assertTrue(
                        "${c.name} 里的 ${part.name} 只能按 ${part.license} 用，要附版权行",
                        part.copyright != null,
                    )
                }
            }
        }
        assertTrue(
            "允许名单的写法",
            allowedIn("Unlicense OR MIT") &&
                !allowedIn("GPL-3.0-only") &&
                !allowedIn("MIT AND GPL-3.0-only"),
        )
    }

    private fun allowedIn(expression: String) =
        Licenses.alternatives(expression).any { alt -> alt.all { it in PERMISSIVE + "Unlicense" } }

    /** 用到的每种许可证都有全文（GPL-3.0 就是仓根的 LICENSE），assets/licenses 里也没有用不到的。 */
    @Test
    fun licenceTextsShip() {
        val used = all.flatMap { Licenses.ids(it.license) }.toSet()
        assertEquals("Licenses.titles 和实际用到的许可证", used, Licenses.titles.keys)
        for (id in used) assertTrue(
            "$id 的全文 ${Licenses.asset(id)} 是空的",
            String(asset(Licenses.asset(id))).isNotBlank(),
        )
        val shipped = app.assets.list("licenses").orEmpty().toSet()
        assertEquals(
            used.map { Licenses.asset(it).removePrefix("licenses/") }.toSet() + "artifacts.json",
            shipped,
        )
        assertEquals(
            "GPL-3.0 的全文要和仓根 LICENSE 一样",
            File(repo, "LICENSE").readText(),
            String(asset(Licenses.asset("GPL-3.0-only"))),
        )
    }

    /** helper/go.mod：Go 版本和 Go 标准库那条一致；require 的每个模块（含按 tool 编进 APK 的）在 GO_MODULES 里各有一条，版本相同。 */
    @Test
    fun goModulesMatchGoMod() {
        val mod = GoMod.parse(File(repo, "helper/go.mod").readText())
        val stdlib = ThirdParty.BUNDLED.single { it.name == "Go 标准库与运行时" }
        assertEquals("Go 标准库那条的版本照 helper/go.mod 的 go 行", mod.go, stdlib.version)
        assertEquals(
            "ThirdParty.GO_MODULES 和 helper/go.mod 的 require",
            mod.require,
            ThirdParty.GO_MODULES.associate { it.name to it.version },
        )
        for (c in ThirdParty.GO_MODULES) assertTrue(
            "${c.name} 也要在 ThirdParty.BUNDLED 里",
            c in ThirdParty.BUNDLED,
        )
    }

    /** gradle/libs.versions.toml：RaTeX 的版本（KaTeX 字体、原生库里的 Rust 代码两条照它核对过）。 */
    @Test
    fun catalogVersionsMatch() {
        val versions =
            File(repo, "gradle/libs.versions.toml")
                .readLines()
                .mapNotNull {
                    Regex("""^([\w-]+)\s*=\s*"([^"]+)"""").find(it)?.destructured?.let { (k, v) ->
                        k to v
                    }
                }
                .toMap()
        assertEquals(
            "升级 RaTeX 后重核 KaTeX 字体和 RATEX_RUST，再改 RATEX_VERSION",
            versions["ratex"],
            ThirdParty.RATEX_VERSION,
        )
        assertEquals(
            ThirdParty.RATEX_VERSION,
            maven.single { "io.github.erweixin:ratex-android" in it.artifacts }.version,
        )
    }

    /** KaTeX 字体：assets/fonts 里的正是列出的那些，字体里写的版权和条目一致。 */
    @Test
    fun katexFontsMatch() {
        val shipped = app.assets.list("fonts").orEmpty().filter { it.startsWith("KaTeX_") }.sorted()
        assertEquals(ThirdParty.KATEX_FONTS.sorted(), shipped)
        val listed =
            ThirdParty.BUNDLED.single { it.name == "KaTeX 字体" }.copyright.joinToString("\n")
        for (font in shipped) {
            // 字体里写的是「Copyright (c) 2009-2010 Design Science, Inc.」这样的两行，条目照许可证声明的写法多了标点和网址
            for (holder in
                FontNames.read(asset("fonts/$font"))[0].orEmpty().lines().map {
                    it.replace(Regex("""^Copyright \(c\) [\d-]+,? *"""), "")
                        .substringBefore(",")
                        .trim()
                }) {
                assertTrue("$font 的版权人 $holder 不在条目里", holder.isNotBlank() && holder in listed)
            }
        }
    }

    /** JetBrains Mono：仓里的两个字体文件，版权行和版本取自字体自己。 */
    @Test
    fun jetBrainsMonoMatches() {
        val mono = ThirdParty.BUNDLED.single { it.name == "JetBrains Mono NL" }
        for (path in mono.files) {
            val names = FontNames.read(File(repo, path).readBytes())
            assertEquals(path, mono.copyright.single(), names[0])
            assertTrue(
                "$path 的版本：${names[5]}",
                names[5].orEmpty().startsWith("Version ${mono.version};"),
            )
        }
        val fonts =
            File(repo, "app/src/main/res/font")
                .list()
                .orEmpty()
                .map { "app/src/main/res/font/$it" }
                .toSet()
        assertEquals("res/font 里的字体都要有条目", fonts, mono.files.toSet())
    }

    @Test
    fun etaFilesExist() {
        for (path in ThirdParty.ETA_FILES) assertTrue(
            "$path 不在了：改 ThirdParty.ETA_FILES",
            File(repo, path).isFile,
        )
    }

    /** 仓根 THIRD_PARTY.md 是从同一份数据生成的；对不上时新的一份写在 app/build/THIRD_PARTY.md。 */
    @Test
    fun noticeFileIsCurrent() {
        val expected = ThirdPartyNotice.render(ThirdParty.BUNDLED, maven)
        val file = File(repo, "THIRD_PARTY.md")
        if (!file.isFile || file.readText() != expected) {
            val fresh = File(repo, "app/build/THIRD_PARTY.md").apply { writeText(expected) }
            fail("THIRD_PARTY.md 过时了：cp ${fresh.relativeTo(repo)} THIRD_PARTY.md")
        }
    }

    private companion object {
        /** 随包组件可以用的许可证，都和 GPL-3.0 兼容（OFL-1.1 的字体和程序分开授权，FSF 认可和 GPL 程序一起分发）。 */
        val PERMISSIVE = setOf("Apache-2.0", "MIT", "BSD-3-Clause", "ISC", "OFL-1.1", "Unicode-3.0")

        /** 只许点名的组件用的：Eta 的 GPL-3.0。 */
        val NAMED = mapOf("Eta" to "GPL-3.0-only")

        /** 要求随包附上版权行的许可证（Apache-2.0 只要求附全文，Unicode-3.0 的全文里自带版权行）。 */
        val NEEDS_NOTICE = setOf("MIT", "BSD-3-Clause", "ISC", "OFL-1.1", "GPL-3.0-only")
    }
}

/** go.mod 里本测试要的：go 行的版本，require 的模块和版本（单行和括号块两种写法都认，`// indirect` 也算）。 */
private data class GoMod(val go: String, val require: Map<String, String>) {
    companion object {
        fun parse(text: String): GoMod {
            var go = ""
            val require = linkedMapOf<String, String>()
            var inBlock = false
            for (raw in text.lines()) {
                val line = raw.substringBefore("//").trim()
                when {
                    line.startsWith("go ") -> go = line.removePrefix("go ").trim()
                    line == "require (" -> inBlock = true
                    inBlock && line == ")" -> inBlock = false
                    inBlock && line.isNotEmpty() ->
                        line.split(Regex("""\s+""")).let { require[it[0]] = it[1] }
                    line.startsWith("require ") ->
                        line.removePrefix("require ").trim().split(Regex("""\s+""")).let {
                            require[it[0]] = it[1]
                        }
                }
            }
            return GoMod(go, require)
        }
    }
}

/** 读 TrueType 字体的 name 表（Windows 平台的 UTF-16 记录）：0 是版权，5 是版本。 */
private object FontNames {
    fun read(font: ByteArray): Map<Int, String> {
        val buffer = ByteBuffer.wrap(font)
        val tables = buffer.getShort(4).toInt() and 0xFFFF
        val record =
            (0 until tables)
                .map { 12 + 16 * it }
                .first { String(font, it, 4, Charsets.US_ASCII) == "name" }
        val table = buffer.getInt(record + 8)
        val count = buffer.getShort(table + 2).toInt() and 0xFFFF
        val strings = table + (buffer.getShort(table + 4).toInt() and 0xFFFF)
        val names = HashMap<Int, String>()
        for (i in 0 until count) {
            val at = table + 6 + 12 * i
            val platform = buffer.getShort(at).toInt()
            val id = buffer.getShort(at + 6).toInt() and 0xFFFF
            val length = buffer.getShort(at + 8).toInt() and 0xFFFF
            val offset = buffer.getShort(at + 10).toInt() and 0xFFFF
            if (platform == 3)
                names.putIfAbsent(id, String(font, strings + offset, length, Charsets.UTF_16BE))
        }
        return names
    }
}
