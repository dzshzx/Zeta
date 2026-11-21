package com.dzshzx.zeta.core.about

/** 仓根 THIRD_PARTY.md 的内容：和设置「开源许可」页同一份数据（[ThirdParty]），由 [ThirdPartyTest] 生成、核对。 */
internal object ThirdPartyNotice {
    fun render(bundled: List<Component>, maven: List<Component>): String = buildString {
        appendLine("# 第三方组件")
        appendLine()
        appendLine(
            "Zeta 本身按 GPL-3.0 授权（[LICENSE](LICENSE)），${ZetaInfo.COPYRIGHT}。APK 里还带着下面这些第三方组件，各自按它们的许可证分发。",
        )
        appendLine("应用里的「设置 › 关于 › 开源许可」列的是同一份，点开一项能看版权行和许可证全文；全文也在")
        appendLine(
            "[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)（GPL-3.0 是构建时从仓根 LICENSE 拷进去的）。",
        )
        appendLine()
        appendLine(
            "这份文件由单元测试 `ThirdPartyTest` 生成，不手改。依赖或条目变了以后测试会失败，照它的提示把 `app/build/THIRD_PARTY.md` 拷过来。",
        )
        appendLine()
        appendLine(
            "- 「随包的其他组件」是手写的条目，在 `app/src/main/kotlin/com/dzshzx/zeta/core/about/ThirdParty.kt`。",
        )
        appendLine(
            "  测试核对它们和仓里的实际情况一致：`helper/go.mod`、`gradle/libs.versions.toml` 里的版本，字体文件里的版权和版本，从 Eta 拷来的文件还在。",
        )
        appendLine(
            "- 「依赖库」由 [licensee](https://github.com/cashapp/licensee) 在构建时从运行时依赖生成，打进 APK 的 `assets/licenses/artifacts.json`。",
        )
        appendLine(
            "  许可证的允许名单在 `app/build.gradle.kts`，不在名单里或认不出的构建失败；POM 只写了网址的许可证和补上的版权行在 `ThirdParty.kt`。",
        )
        appendLine()
        appendLine("## 随包的其他组件")
        for (c in bundled) bundledSection(c)
        appendLine()
        appendLine("## 依赖库")
        appendLine()
        appendLine("| 组件 | 版本 | 许可证 | 构件 | 上游 |")
        appendLine("|---|---|---|---|---|")
        for (c in maven) appendLine(
            "| ${cell(c.name)} | ${c.version} | ${c.license} | ${c.artifacts.joinToString("<br>") { "`$it`" }} | ${c.url.orEmpty()} |",
        )
        val withCopyright = maven.filter { it.copyright.isNotEmpty() }
        if (withCopyright.isNotEmpty()) {
            appendLine()
            appendLine("MIT、BSD、ISC 要求随包附上的版权行：")
            appendLine()
            for (c in withCopyright) appendLine("- ${c.name}：${c.copyright.joinToString("；")}")
        }
    }

    private fun StringBuilder.bundledSection(c: Component) {
        appendLine()
        appendLine("### ${c.name}")
        appendLine()
        appendLine("| 版本 | 许可证 | 上游 |")
        appendLine("|---|---|---|")
        appendLine("| ${c.version} | ${c.license} | ${c.url.orEmpty()} |")
        c.note?.let {
            appendLine()
            appendLine(it)
        }
        if (c.copyright.isNotEmpty()) {
            appendLine()
            c.copyright.forEach { appendLine("    $it") }
        }
        if (c.files.isNotEmpty()) {
            appendLine()
            appendLine("文件：")
            appendLine()
            c.files.forEach { appendLine("- `$it`") }
        }
        if (c.parts.isNotEmpty()) {
            appendLine()
            appendLine("| 部分 | 版本 | 许可证 | 版权行 |")
            appendLine("|---|---|---|---|")
            c.parts.forEach {
                appendLine(
                    "| ${cell(it.name)} | ${it.version} | ${it.license} | ${it.copyright.orEmpty()} |",
                )
            }
        }
    }

    private fun cell(text: String) = text.replace("|", "\\|")
}
