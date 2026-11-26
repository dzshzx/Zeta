package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一张卡片里的一行，卡片跨好几个列表项（照 Eta 的 PreferenceGroupItem）：几百行的长列表（运行日志、日志全文）逐行放进 LazyColumn 时用，
 * [PreferenceGroup] 要一次组合整组，放不了这么多。[index]、[count] 定这一行在卡片里的位置：头一行画上面两个圆角，末一行画下面两个圆角并和下一张卡片隔 12dp；和
 * [PreferenceGroup] 一样行间不画分隔线。卡片下面紧跟说明文字（[FooterHint]）时传 [footerBelow]，末一行不再留 12dp。
 */
@Composable
fun CardListSegment(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    footerBelow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val last = index == count - 1
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = if (last && !footerBelow) 12.dp else 0.dp)
            .then(segmentSurface(first = index == 0, last = last)),
        content = content,
    )
}

/** 一行的卡片底：头一行上面两个角是圆的，末一行下面两个角是圆的，中间的行是直角。 */
@Composable
private fun segmentSurface(first: Boolean, last: Boolean): Modifier {
    val color = MiuixTheme.colorScheme.surfaceContainer
    if (!first && !last) return Modifier.background(color)
    val top = if (first) CardDefaults.CornerRadius else 0.dp
    val bottom = if (last) CardDefaults.CornerRadius else 0.dp
    return Modifier.squircleSurface(
        color = color,
        topStart = top,
        topEnd = top,
        bottomStart = bottom,
        bottomEnd = bottom,
    )
}
