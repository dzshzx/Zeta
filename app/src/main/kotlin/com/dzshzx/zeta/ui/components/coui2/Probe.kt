package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.Composable
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ThemeController

@Composable
internal fun Coui2Probe() {
    COUITheme(controller = ThemeController()) {
        Card { CardListGroup { item { SwitchPreference(title = "x", checked = true, onCheckedChange = {}) } } }
        Text("x", style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelPrimary)
        InfiniteProgressIndicator()
    }
}
