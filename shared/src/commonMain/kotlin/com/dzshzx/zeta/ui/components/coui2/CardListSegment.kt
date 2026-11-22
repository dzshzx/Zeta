package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CardListPosition
import io.github.suqi8.coui.kmp.basic.DividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.LocalCardListPosition
import io.github.suqi8.coui.kmp.basic.cardListPositionOf
import io.github.suqi8.coui.kmp.squircle.squircleSurface
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 一张卡片里的一行，卡片跨好几个列表项：几百行的长列表（运行日志、日志全文）逐行放进 LazyColumn 时用， CardListGroup
 * 要一次组合整组，放不了这么多。[index]、[count] 定这一行在卡片里的位置：头一行画上面两个圆角， 末一行画下面两个圆角并和下一张卡片隔 16dp，行间画 COUI 的分隔线。 行里的
 * BasicComponent 照 CardListGroup 里一样拿到自己的位置（头尾多 2dp）。 卡片下面紧跟说明文字（PagerFooterPreference）时传
 * [footerBelow]，末一行不再留 16dp。 [dividerStart] 是分隔线左端离卡片边的距离：行首有图标时传行的左内边距、图标宽和图标与标题的间距之和，让线从标题开始；null
 * 不画分隔线。
 */
@Composable
fun CardListSegment(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    footerBelow: Boolean = false,
    dividerStart: Dp? = DividerDefaults.CardInset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val position = cardListPositionOf(index, count)
    val top =
        if (position == CardListPosition.Head || position == CardListPosition.Full)
            CardDefaults.CornerRadius
        else 0.dp
    val bottom =
        if (position == CardListPosition.Tail || position == CardListPosition.Full)
            CardDefaults.CornerRadius
        else 0.dp
    val last = index == count - 1
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = if (last && !footerBelow) 16.dp else 0.dp)
            .squircleSurface(
                COUITheme.colorScheme.cardBackground,
                top,
                top,
                bottom,
                bottom,
                COUIRoundCorner.Normal.Weight,
            ),
    ) {
        CompositionLocalProvider(LocalCardListPosition provides position) { content() }
        if (!last && dividerStart != null)
            HorizontalDivider(
                Modifier.padding(start = dividerStart, end = DividerDefaults.CardInset),
            )
    }
}
