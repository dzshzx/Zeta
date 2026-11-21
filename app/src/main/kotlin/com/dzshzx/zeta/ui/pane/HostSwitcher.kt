package com.dzshzx.zeta.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.ui.components.coui2.AnchoredMenu
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 换主机的小胶囊：显示侧栏正看着的主机（[ConversationHub.backend]），点开列出手机和各台远程主机，选一台就换过去 （会话列表、新对话都跟着换，见
 * [ConversationHub.selectBackend]；各台主机的设置在设置首页里分开列）。还没加远程主机时不显示。 侧栏顶部和新对话的空白页上各有一个。
 */
@Composable
internal fun HostSwitcher(
    hub: ConversationHub,
    modifier: Modifier = Modifier,
    onSelected: () -> Unit = {},
) {
    val hosts = hub.settings.remoteHosts
    if (hosts.isEmpty()) return
    val colors = COUITheme.colorScheme
    val current = hub.backend
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier =
                Modifier.clip(CircleShape)
                    .background(colors.labelPrimary.copy(alpha = 0.05f))
                    .clickable { open = true }
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = hostIcon(current),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = hub.backendName(current),
                style = COUITheme.textStyles.buttonM,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = ZetaIcons.ChevronDown,
                contentDescription = "换设备",
                modifier = Modifier.size(14.dp),
                tint = colors.labelSecondary,
            )
        }
        // 编号、名字、菜单里的小字
        val items =
            listOf(
                Triple(Backends.LOCAL, hub.backendName(Backends.LOCAL), RemoteHosts.LOCAL_SUMMARY),
            ) + hosts.map { Triple(it.id, it.name, it.target) }
        AnchoredMenu(
            show = open,
            entries =
                listOf(
                    DropdownEntry(
                        items =
                            items.map { (id, name, summary) ->
                                menuItem(
                                        name,
                                        hostIcon(id),
                                        onClick = {
                                            hub.selectBackend(id)
                                            onSelected()
                                        },
                                    )
                                    .copy(selected = id == current, summary = summary)
                            },
                    ),
                ),
            onDismiss = { open = false },
        )
    }
}

/** 主机 [backend] 的图标：手机或服务器。换主机的菜单和设置首页的主机一组都用它。 */
internal fun hostIcon(backend: String) =
    if (backend == Backends.LOCAL) ZetaIcons.Phone else ZetaIcons.Server
