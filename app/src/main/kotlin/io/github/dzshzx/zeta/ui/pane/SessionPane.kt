package io.github.dzshzx.zeta.ui.pane

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MapsUgc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import io.github.dzshzx.zeta.core.SessionSummary
import io.github.dzshzx.zeta.ui.components.StatusError
import io.github.dzshzx.zeta.ui.components.conversationPaneContainerColor
import io.github.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import io.github.dzshzx.zeta.ui.components.coui.CouiInputDialog
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PaneHorizontalPadding = 16.dp
private val RowHorizontalPadding = 14.dp

/** 侧栏：品牌头 + 新对话、按“今天 / 更早”分组的历史会话、底部设置入口。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionPane(
    width: Dp,
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    isRunning: (String) -> Boolean,
    onNewChat: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onRename: (SessionSummary, String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    val lastDeleting = remember { arrayOfNulls<SessionSummary>(1) }
    deleting?.let { lastDeleting[0] = it }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val startRename: (SessionSummary) -> Unit = { session ->
        renameDraft = session.title.ifBlank { session.preview.take(40) }
        renaming = session
    }
    val todayStart = remember(sessions) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val today = remember(sessions, todayStart) { sessions.filter { it.updated >= todayStart } }
    val earlier = remember(sessions, todayStart) { sessions.filter { it.updated < todayStart } }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxSize()
            .background(conversationPaneContainerColor())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = PaneHorizontalPadding + 4.dp, end = PaneHorizontalPadding - 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = CouiTheme.colorScheme.accent
            val badge = remember(accent, dark) { Brush.linearGradient(listOf(accent, lerp(accent, if (dark) Color.White else Color.Black, 0.12f))) }
            Box(Modifier.size(26.dp).clip(CircleShape).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                Text(text = "ζ", style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Zeta",
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            PaneCircleButton(icon = Icons.Rounded.MapsUgc, description = "新对话", onClick = onNewChat)
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (sessions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                        Text(text = "还没有对话", style = MiuixTheme.textStyles.body2, color = colors.summary)
                    }
                }
            }
            if (today.isNotEmpty()) {
                item(key = "h-today") { SectionHeader("今天", first = true) }
                items(today, key = { it.id }) { session ->
                    SessionRow(session, session.id == currentSessionId, isRunning(session.id), { onOpen(session) }, { startRename(session) }, { deleting = session })
                }
            }
            if (earlier.isNotEmpty()) {
                item(key = "h-earlier") { SectionHeader("更早", first = today.isEmpty()) }
                items(earlier, key = { it.id }) { session ->
                    SessionRow(session, session.id == currentSessionId, isRunning(session.id), { onOpen(session) }, { startRename(session) }, { deleting = session })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            PaneCircleButton(icon = Icons.Outlined.Settings, description = "设置", onClick = onOpenSettings, size = 44.dp)
        }
    }

    CouiConfirmDialog(
        visible = deleting != null,
        title = "删除这个对话？",
        message = lastDeleting[0]?.let { it.title.ifBlank { it.preview }.take(60) + "\n会话记录文件会被一并删除，无法恢复。" },
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            deleting?.let(onDelete)
            deleting = null
        },
        onDismissRequest = { deleting = null },
    )
    CouiInputDialog(
        visible = renaming != null,
        title = "重命名",
        value = renameDraft,
        onValueChange = { renameDraft = it },
        placeholder = "对话标题",
        confirmText = "确定",
        confirmEnabled = renameDraft.isNotBlank(),
        onConfirm = {
            renaming?.let { onRename(it, renameDraft) }
            renaming = null
        },
        onDismissRequest = { renaming = null },
    )
}

@Composable
private fun PaneCircleButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 40.dp) {
    val dark = CouiTheme.isDark
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (dark) Color(0xFF252830) else Color.White, CircleShape)
            .border(0.5.dp, if (dark) Color(0x1EFFFFFF) else Color(0x12000000), CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(19.dp), tint = CouiPageDefaults.themeColors(dark).title)
    }
}

@Composable
private fun SectionHeader(label: String, first: Boolean) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = CouiPageDefaults.themeColors(CouiTheme.isDark).summary,
        modifier = Modifier.fillMaxWidth().padding(
            start = PaneHorizontalPadding + RowHorizontalPadding,
            end = PaneHorizontalPadding + RowHorizontalPadding,
            top = if (first) 8.dp else 20.dp,
            bottom = 6.dp,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val shape = remember { CouiShape(14.dp, 2.1f) }
    val haptic = LocalHapticFeedback.current
    val anchor = rememberCouiMenuAnchor()
    var showMenu by remember { mutableStateOf(false) }
    val deleteTint = StatusError
    val menuItems = remember(deleteTint) {
        listOf(
            CouiMenuItem(value = 1, text = "重命名", icon = Icons.Rounded.Edit),
            CouiMenuItem(value = 0, text = "删除", icon = Icons.Rounded.DeleteOutline, iconTint = deleteTint),
        )
    }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 1.5.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .couiMenuAnchor(anchor)
                .then(if (selected && !dark) Modifier.dropShadow(shape = shape, shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.028f)) else Modifier)
                .clip(shape)
                .background(if (!selected) Color.Transparent else if (dark) Color(0xFF262932) else Color.White, shape)
                .then(if (selected) Modifier.border(0.5.dp, if (dark) Color(0x1AFFFFFF) else Color(0x0D000000), shape) else Modifier)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor.open { showMenu = true }
                    },
                )
                .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { session.preview }.ifBlank { "未命名对话" },
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                    color = colors.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = session.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" } + if (session.external) " · 别处运行中" else "",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (running) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(6.dp).background(CouiTheme.colorScheme.accent, CircleShape))
            }
        }
        Menu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            items = menuItems,
            onItemClick = {
                showMenu = false
                if (it.value == 1) onRename() else onDelete()
            },
            anchor = anchor,
        )
    }
}
