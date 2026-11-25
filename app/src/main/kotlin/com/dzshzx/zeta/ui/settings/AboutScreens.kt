package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.about.Component
import com.dzshzx.zeta.core.about.Licenses
import com.dzshzx.zeta.core.about.ThirdParty
import com.dzshzx.zeta.core.about.ZetaInfo
import com.dzshzx.zeta.core.update.AppUpdates
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.setup.ConfigLoadingRow
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Favorites
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置首页的「关于」一组：版本（正式版点它检查更新，见 UpdateScreens.kt）、自动检查更新、源码、许可证（GPL 要求界面上能看到的版权和无担保声明在那一页）、开源许可、鸣谢、诊断，
 * 组下一行非官方声明。
 */
@Composable
internal fun AboutGroup(onOpenSubPage: (SettingsSubPage) -> Unit) {
    val updates = AppUpdates.get(LocalContext.current)
    PreferenceGroup(title = Strings.aboutTitle(), footer = ZetaInfo.unofficial) {
        item { VersionRow(updates, onOpenUpdate = { onOpenSubPage(SettingsSubPage.Update) }) }
        if (updates.enabled) item { AutoUpdateSwitch() }
        item {
            val context = LocalContext.current
            ArrowPreference(
                title = Strings.aboutSource(),
                startAction = { SettingsIcon(ZetaIcons.Branch) },
                endActions = {
                    PreferenceValue(ZetaInfo.sourceRef(BuildConfig.VERSION_NAME) ?: "GitHub")
                },
                onClick = { openLink(context, ZetaInfo.sourceUrl(BuildConfig.VERSION_NAME)) },
            )
        }
        item {
            ArrowPreference(
                title = Strings.aboutLicense(),
                startAction = { SettingsIcon(COUIIcons.Notes) },
                endActions = { PreferenceValue("GPL-3.0") },
                onClick = { onOpenSubPage(SettingsSubPage.License) },
            )
        }
        item {
            ArrowPreference(
                title = Strings.aboutOpenSource(),
                startAction = { SettingsIcon(COUIIcons.Layers) },
                onClick = { onOpenSubPage(SettingsSubPage.OpenSource) },
            )
        }
        item {
            ArrowPreference(
                title = Strings.aboutCredits(),
                startAction = { SettingsIcon(COUIIcons.Favorites) },
                onClick = { onOpenSubPage(SettingsSubPage.Credits) },
            )
        }
        item {
            ArrowPreference(
                title = Strings.aboutDiagnostics(),
                startAction = { SettingsIcon(ZetaIcons.Pulse) },
                onClick = { onOpenSubPage(SettingsSubPage.Diagnostics) },
            )
        }
    }
}

/** 「许可证」：Zeta 的版权、GPL 要求的声明，下面是 GPL-3.0 全文（仓根 LICENSE）。 */
@Composable
internal fun LicenseScreen(onBack: () -> Unit) {
    val text = rememberAssetText(Licenses.asset("GPL-3.0-only"))
    CouiPage(title = Strings.aboutLicense(), onBack = onBack) {
        item(key = "zeta") {
            PreferenceGroup(footer = ZetaInfo.legalNotice.joinToString("\n")) {
                item { BasicComponent(title = "Zeta", summary = ZetaInfo.COPYRIGHT) }
                item { ValueRow(Strings.aboutLicense(), "GPL-3.0") }
            }
        }
        licenseText("GPL-3.0-only", text)
    }
}

/** 「开源许可」：手写的条目在上，Maven 依赖（构建时生成的清单）在下；点一项看它的版权和许可证全文。 */
@Composable
internal fun OpenSourceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val maven by
        produceState<List<Component>?>(null) {
            value = withContext(Dispatchers.IO) { ThirdParty.loadMaven(context) }
        }
    var open by remember { mutableStateOf<Component?>(null) }
    Box(Modifier.fillMaxSize()) {
        CouiPage(title = Strings.aboutOpenSource(), onBack = onBack) {
            item(key = "bundled") {
                PreferenceGroup(
                    title = Strings.aboutBundled(),
                    footer = Strings.aboutBundledFooter(),
                ) {
                    ThirdParty.BUNDLED.forEach { component ->
                        item { ComponentRow(component) { open = component } }
                    }
                }
            }
            item(key = "maven-title") { PreferenceCategory(title = Strings.aboutLibraries()) }
            val list = maven
            if (list == null) {
                item(key = "maven-loading") { PreferenceGroup { item { ConfigLoadingRow() } } }
            } else {
                itemsIndexed(list, key = { _, c -> "m:${c.artifacts.first()}:${c.version}" }) {
                    index,
                    component ->
                    CardListSegment(index, list.size, footerBelow = true) {
                        ComponentRow(component) { open = component }
                    }
                }
                item(key = "maven-footer") {
                    PagerFooterPreference(
                        summary = Strings.aboutLibrariesFooter(list.size),
                    )
                }
            }
        }
        SlideInPage(open, onBack = { open = null }) {
            ComponentScreen(it, onBack = { open = null })
        }
    }
}

@Composable
private fun ComponentRow(component: Component, onClick: () -> Unit) {
    ArrowPreference(
        title = component.name,
        summary = "${component.version} · ${component.license}",
        onClick = onClick,
    )
}

/** 一个组件：版本、许可证、上游，版权行、包含的部分，再是用到的每种许可证的全文。 */
@Composable
internal fun ComponentScreen(component: Component, onBack: () -> Unit) {
    val context = LocalContext.current
    val ids = Licenses.ids(component.license)
    val texts = ids.map { rememberAssetText(Licenses.asset(it)) }
    CouiPage(title = component.name, onBack = onBack) {
        item(key = "info") {
            PreferenceGroup(footer = component.note) {
                item { ValueRow(Strings.commonVersion(), component.version) }
                item { BasicComponent(title = Strings.aboutLicense(), summary = component.license) }
                component.url?.let { url ->
                    item {
                        ArrowPreference(
                            title = Strings.aboutUpstream(),
                            summary = url,
                            onClick = { openLink(context, url) },
                        )
                    }
                }
            }
        }
        if (component.copyright.isNotEmpty()) {
            item(key = "copyright") {
                PreferenceGroup(title = Strings.aboutCopyright()) {
                    item { TextBlock(component.copyright) }
                }
            }
        }
        if (component.parts.isNotEmpty()) {
            item(key = "parts") {
                PreferenceGroup(title = Strings.aboutIncludes()) {
                    component.parts.forEach { part ->
                        item {
                            BasicComponent(
                                title = part.name,
                                summary =
                                    listOfNotNull(
                                            "${part.version} · ${part.license}",
                                            part.copyright,
                                        )
                                        .joinToString("\n"),
                            )
                        }
                    }
                }
            }
        }
        if (component.files.isNotEmpty()) {
            // Kotlin 源码的路径去掉公共的前缀，手机上一行放得下
            item(key = "files") {
                PreferenceGroup(title = Strings.aboutRepoFiles()) {
                    item {
                        TextBlock(
                            component.files.map {
                                it.removePrefix(ThirdParty.APP_SOURCES)
                                    .removePrefix(ThirdParty.SHARED_SOURCES)
                            },
                        )
                    }
                }
            }
        }
        if (component.artifacts.isNotEmpty()) {
            item(key = "artifacts") {
                PreferenceGroup(title = Strings.aboutArtifacts()) {
                    item { TextBlock(component.artifacts) }
                }
            }
        }
        ids.forEachIndexed { index, id -> licenseText(id, texts[index]) }
    }
}

/** 「鸣谢」：Zeta 参照和依靠的几个项目，点一行打开它的主页。 */
@Composable
internal fun CreditsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    CouiPage(title = Strings.aboutCredits(), onBack = onBack) {
        item(key = "credits") {
            PreferenceGroup(footer = ZetaInfo.unofficial) {
                CREDITS.forEach { credit ->
                    item {
                        ArrowPreference(
                            title = credit.name,
                            summary = credit.summary(),
                            onClick = { openLink(context, credit.url) },
                        )
                    }
                }
            }
        }
    }
}

/** 鸣谢的一项：介绍（[summary]）是我们自己写的，显示时按界面语言取字。 */
private data class Credit(val name: String, val summary: () -> String, val url: String)

private val CREDITS =
    listOf(
        Credit(
            "Eta",
            Strings::aboutCreditEta,
            "https://github.com/Mangi-11/Eta",
        ),
        Credit("COUI 2", Strings::aboutCreditCoui, "https://github.com/suqi8/coui"),
        Credit("Miuix", Strings::aboutCreditMiuix, "https://github.com/compose-miuix-ui/miuix"),
        Credit(
            "Claude Code",
            Strings::aboutCreditClaudeCode,
            "https://github.com/anthropics/claude-code",
        ),
    )

/** 一种许可证的全文：分类标题，下面一张卡片里分段排；还没读出来时转圈，读不出时说一声。 */
private fun LazyListScope.licenseText(id: String, text: String?) {
    item(key = "title:$id") { PreferenceCategory(title = Licenses.titles[id] ?: id) }
    val paragraphs = text?.let { Licenses.paragraphs(it) }
    when {
        paragraphs == null ->
            item(key = "loading:$id") { PreferenceGroup { item { ConfigLoadingRow() } } }
        paragraphs.isEmpty() ->
            item(key = "missing:$id") {
                PagerFooterPreference(summary = Strings.aboutLicenseTextMissing(Licenses.asset(id)))
            }
        else -> {
            // 几段一组放进列表，长的全文滚动时只组合看得见的那几组
            val chunks = paragraphs.chunked(8)
            itemsIndexed(chunks, key = { index, _ -> "text:$id:$index" }) { index, chunk ->
                CardListSegment(index, chunks.size, dividerStart = null) {
                    SelectionContainer {
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = if (index == 0) 14.dp else 0.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            chunk.forEach {
                                Text(
                                    text = it,
                                    style = COUITheme.textStyles.bodyS,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 卡片里的几行小字（版权行、文件路径、构件坐标），能选中复制。 */
@Composable
private fun TextBlock(lines: List<String>) {
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lines.forEach {
                Text(
                    text = it,
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelPrimary,
                )
            }
        }
    }
}

/** assets 里的一份文本，在后台读；读不出时是空字符串。 */
@Composable
private fun rememberAssetText(path: String): String? {
    val context = LocalContext.current
    val text by
        produceState<String?>(null, path) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                        .getOrDefault("")
                }
        }
    return text
}

/** 用系统浏览器打开链接。 */
private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
