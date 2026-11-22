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
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
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
    PreferenceGroup(title = "关于", footer = ZetaInfo.UNOFFICIAL) {
        item { VersionRow(updates, onOpenUpdate = { onOpenSubPage(SettingsSubPage.Update) }) }
        if (updates.enabled) item { AutoUpdateSwitch() }
        item {
            val context = LocalContext.current
            ArrowPreference(
                title = "源码",
                startAction = { SettingsIcon(ZetaIcons.Branch, SettingsHue.System) },
                endActions = {
                    PreferenceValue(ZetaInfo.sourceRef(BuildConfig.VERSION_NAME) ?: "GitHub")
                },
                onClick = { openLink(context, ZetaInfo.sourceUrl(BuildConfig.VERSION_NAME)) },
            )
        }
        item {
            ArrowPreference(
                title = "许可证",
                startAction = { SettingsIcon(COUIIcons.Notes, SettingsHue.System) },
                endActions = { PreferenceValue("GPL-3.0") },
                onClick = { onOpenSubPage(SettingsSubPage.License) },
            )
        }
        item {
            ArrowPreference(
                title = "开源许可",
                startAction = { SettingsIcon(COUIIcons.Layers, SettingsHue.System) },
                onClick = { onOpenSubPage(SettingsSubPage.OpenSource) },
            )
        }
        item {
            ArrowPreference(
                title = "鸣谢",
                startAction = { SettingsIcon(COUIIcons.Favorites, SettingsHue.System) },
                onClick = { onOpenSubPage(SettingsSubPage.Credits) },
            )
        }
        item {
            ArrowPreference(
                title = "诊断与日志",
                startAction = { SettingsIcon(ZetaIcons.Pulse, SettingsHue.Files) },
                onClick = { onOpenSubPage(SettingsSubPage.Diagnostics) },
            )
        }
    }
}

/** 「许可证」：Zeta 的版权、GPL 要求的声明，下面是 GPL-3.0 全文（仓根 LICENSE）。 */
@Composable
internal fun LicenseScreen(onBack: () -> Unit) {
    val text = rememberAssetText(Licenses.asset("GPL-3.0-only"))
    CouiPage(title = "许可证", onBack = onBack) {
        item(key = "zeta") {
            PreferenceGroup(footer = ZetaInfo.LEGAL_NOTICE.joinToString("\n")) {
                item { BasicComponent(title = "Zeta", summary = ZetaInfo.COPYRIGHT) }
                item { BasicComponent(title = "许可证", endActions = { PreferenceValue("GPL-3.0") }) }
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
        CouiPage(title = "开源许可", onBack = onBack) {
            item(key = "bundled") {
                PreferenceGroup(
                    title = "随包的其他组件",
                    footer = "不在依赖库里的：改写自 Eta 的代码、字体、原生库和 zeta-helper 里的代码。",
                ) {
                    ThirdParty.BUNDLED.forEach { component ->
                        item { ComponentRow(component) { open = component } }
                    }
                }
            }
            item(key = "maven-title") { PreferenceCategory(title = "依赖库") }
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
                        summary = "共 ${list.size} 项，构建时由 licensee 从这个版本打包的依赖生成，许可证不在允许名单里的构建不过。",
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
                item {
                    BasicComponent(
                        title = "版本",
                        endActions = { PreferenceValue(component.version) },
                    )
                }
                item { BasicComponent(title = "许可证", summary = component.license) }
                component.url?.let { url ->
                    item {
                        ArrowPreference(
                            title = "上游",
                            summary = url,
                            onClick = { openLink(context, url) },
                        )
                    }
                }
            }
        }
        if (component.copyright.isNotEmpty()) {
            item(key = "copyright") {
                PreferenceGroup(title = "版权") { item { TextBlock(component.copyright) } }
            }
        }
        if (component.parts.isNotEmpty()) {
            item(key = "parts") {
                PreferenceGroup(title = "包含") {
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
                PreferenceGroup(title = "仓里的文件") {
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
                PreferenceGroup(title = "构件") { item { TextBlock(component.artifacts) } }
            }
        }
        ids.forEachIndexed { index, id -> licenseText(id, texts[index]) }
    }
}

/** 「鸣谢」：Zeta 参照和依靠的几个项目，点一行打开它的主页。 */
@Composable
internal fun CreditsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    CouiPage(title = "鸣谢", onBack = onBack) {
        item(key = "credits") {
            PreferenceGroup(footer = ZetaInfo.UNOFFICIAL) {
                CREDITS.forEach { credit ->
                    item {
                        ArrowPreference(
                            title = credit.name,
                            summary = credit.summary,
                            onClick = { openLink(context, credit.url) },
                        )
                    }
                }
            }
        }
    }
}

private data class Credit(val name: String, val summary: String, val url: String)

private val CREDITS =
    listOf(
        Credit(
            "Eta",
            "Zeta 的功能和交互照它设计；Markdown 渲染、侧栏骨架的代码来自它（GPL-3.0）",
            "https://github.com/Mangi-11/Eta",
        ),
        Credit("COUI 2", "界面组件库，ColorOS 17 的样式（Apache-2.0）", "https://github.com/suqi8/coui"),
        Credit("Miuix", "COUI 2 的上游（Apache-2.0）", "https://github.com/compose-miuix-ui/miuix"),
        Credit(
            "Claude Code",
            "Anthropic 的编程 agent，Zeta 是它在手机上的客户端",
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
                PagerFooterPreference(summary = "读不出这份全文（${Licenses.asset(id)}）")
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
