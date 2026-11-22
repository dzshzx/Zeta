package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 设置页的一组：分类标题、圆角卡片里的一组设置行、卡片下面的说明文字。 行用 COUI 2
 * 的设置项（SwitchPreference、ArrowPreference、OverlayDropdownPreference、BasicComponent……）写在 `item { }` 里；
 * 分隔线和行在卡片里的位置由 CardListGroup 管，不自己画分隔线、不设 cardListPosition。
 */
@Composable
fun PreferenceGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: CardListGroupScope.() -> Unit,
) {
    Column(modifier) {
        if (title != null) PreferenceCategory(title = title)
        // 卡片离屏幕边 16dp、离下一张卡片 16dp，COUI 不自带，要自己加
        Card(
            Modifier.padding(horizontal = 16.dp)
                .padding(bottom = if (footer == null) 16.dp else 0.dp),
        ) {
            CardListGroup(content = content)
        }
        if (footer != null) PagerFooterPreference(summary = footer)
    }
}

/**
 * 设置行右侧显示的当前值，放进行的 endActions：`endActions = { PreferenceValue("跟随系统") }`。 要按状态上色（出错、需关注）时给
 * [color]，Unspecified 时是普通值的颜色。
 */
@Composable
fun PreferenceValue(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        style = COUITheme.textStyles.bodyM,
        color = color.takeOrElse { COUITheme.colorScheme.labelTertiary },
        maxLines = 1,
    )
}
