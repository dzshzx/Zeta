package com.dzshzx.zeta.ui.components.coui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 页面里不在分组中的一段提示文字（列表为空、读不出来等）；[error] 时用错误色。 */
@Composable
internal fun CouiHint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
    )
}
