package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.ui.components.MonoFont
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 对话框里一层层点选目录用的两样（添加共享文件夹、附加手机上的文件夹）：Miuix 没有对话框里的目录列表，自己排。

/** 目录列表的一行：主题色的文件夹图标加等宽的名字，点了进去。 */
@Composable
fun FolderPickerRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2.copy(fontFamily = MonoFont),
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 目录列表上下的一句小字：没有子目录、读不出来、填得不对；[error] 时是错误色。 */
@Composable
fun FolderPickerNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color =
            if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
