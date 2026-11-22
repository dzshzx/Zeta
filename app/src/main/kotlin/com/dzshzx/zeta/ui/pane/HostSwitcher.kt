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
 * 侧栏顶部那个（[forList]）管会话列表列哪台的：多一项「全部设备」，各台的会话合在一起列（[ConversationHub.selectAllHosts]），
 * 选着它时胶囊上写「全部设备」；空白页上那个只管新对话开在哪台，不改列表列哪些。
 */
@Composable
internal fun HostSwitcher(
    hub: ConversationHub,
    modifier: Modifier = Modifier,
    forList: Boolean = false,
    onSelected: () -> Unit = {},
) {
    val hosts = hub.settings.remoteHosts
    if (hosts.isEmpty()) return
    val colors = COUITheme.colorScheme
    val current = hub.backend
    val all = forList && hub.allHosts
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
                imageVector = if (all) ZetaIcons.Globe else hostIcon(current),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (all) AllHostsLabel else hub.backendName(current),
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
        AnchoredMenu(
            show = open,
            entries = hostMenu(hub, forList, all, onSelected),
            onDismiss = { open = false },
        )
    }
}

/** 设备胶囊的菜单：[forList] 时先是「全部设备」，然后是手机和各台远程主机（名字下面的小字是地址）。 */
private fun hostMenu(
    hub: ConversationHub,
    forList: Boolean,
    all: Boolean,
    onSelected: () -> Unit,
): List<DropdownEntry> {
    val pick: (String) -> Unit = if (forList) hub::listOnly else hub::selectBackend
    val items =
        listOf(Triple(Backends.LOCAL, hub.backendName(Backends.LOCAL), RemoteHosts.LOCAL_SUMMARY)) +
            hub.settings.remoteHosts.map { Triple(it.id, it.name, it.target) }
    val hosts =
        DropdownEntry(
            items =
                items.map { (id, name, summary) ->
                    menuItem(
                            name,
                            hostIcon(id),
                            onClick = {
                                pick(id)
                                onSelected()
                            },
                        )
                        .copy(selected = id == hub.backend && !all, summary = summary)
                },
        )
    val allHosts =
        allHostsEntry(all) {
            hub.selectAllHosts()
            onSelected()
        }
    return if (forList) listOf(allHosts, hosts) else listOf(hosts)
}

/** 设备胶囊里列全部设备那一项的名字。 */
internal const val AllHostsLabel = "全部设备"

/** 菜单里「全部设备」那一组；[selected] 是正列着全部设备。 */
private fun allHostsEntry(selected: Boolean, onClick: () -> Unit) =
    DropdownEntry(
        items =
            listOf(
                menuItem(AllHostsLabel, ZetaIcons.Globe, onClick = onClick)
                    .copy(selected = selected, summary = "各台设备的对话列在一起"),
            ),
    )

/** 主机 [backend] 的图标：手机或服务器。换主机的菜单和设置首页的主机一组都用它。 */
internal fun hostIcon(backend: String) =
    if (backend == Backends.LOCAL) ZetaIcons.Phone else ZetaIcons.Server
