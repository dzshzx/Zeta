package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.i18n.AppLanguage
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Translate
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference

/**
 * 设置首页「通用」里选界面语言的一行（docs/i18n.md）：跟随系统、简体中文、English，语言名用各自的写法。选的存在 Android 的应用语言里
 * （[AppLanguage]），和系统设置「应用语言」里改的是同一个；改了以后 Android 重建界面，这一页跟着换成新语言。
 */
@Composable
internal fun LanguageRow() {
    val context = LocalContext.current
    val choices = listOf<String?>(null) + UiLanguage.supported
    OverlayDropdownPreference(
        title = Strings.settingsLanguage(),
        startAction = { SettingsIcon(COUIIcons.Translate, SettingsHue.System) },
        items = choices.map { it?.let(UiLanguage::autonym) ?: Strings.settingsLanguageSystem() },
        selectedIndex = choices.indexOf(AppLanguage.choice(context)).coerceAtLeast(0),
        onSelectedIndexChange = { index -> AppLanguage.choose(context, choices[index]) },
    )
}
