package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.i18n.Strings
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassSecondaryPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.layout.CascadingPopupDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

// 菜单照 Eta 的玻璃菜单（ConversationPanePanel.kt 的「更多」、GlassSpinnerPreference.kt 的挂法）：HyperOS 4 的玻璃弹层，
// 行是 Miuix 的 GlassPopupItem（图标在文字前，警示项文字和图标都是红色），组与组之间一条分隔线，二级菜单是 GlassSecondaryPopup。
// 弹层挂在最外层的 Miuix Scaffold 上，拿页面背景（LocalPageBackdrop）做毛玻璃。

/**
 * 菜单里的一项。[icon] 画在文字前面；[alert] 的（删除这类）文字和图标都是红色；[selected] 的打勾；[children] 非空时点它在旁边弹出下一层， 这一项自己的
 * [onClick] 不用。
 */
@Immutable
data class MenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val onClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val alert: Boolean = false,
    val selected: Boolean = false,
    val summary: String? = null,
    val children: List<MenuItem>? = null,
)

/** 菜单里的一组，组与组之间有分隔线；[enabled] 为 false 时整组不能点。 */
@Immutable data class MenuEntry(val items: List<MenuItem>, val enabled: Boolean = true)

/**
 * 挂在旁边元素（胶囊、按钮、标题、设置行）上的下拉菜单，点一项就收起。和那个元素放进同一个 Box：`Box { 元素; AnchoredMenu(show, entries,
 * onDismiss) }`，菜单以那个 Box 为锚弹出。项可以带二级菜单（[MenuItem.children]），点那一项在旁边弹出下一层。 对齐由锚定（MenuPopup）：调用方不选。
 */
@Composable
fun BoxScope.AnchoredMenu(show: Boolean, entries: List<MenuEntry>, onDismiss: () -> Unit) =
    anchoredMenu(show, entries, onDismiss, alignEnd = false)

/** [AnchoredMenu] 本身；[alignEnd] 只给本文件的图标按钮（[IconMenuButton]）用：它们在行尾，菜单向左展开，居中会伸出所在的面板。 */
@Composable
private fun BoxScope.anchoredMenu(
    show: Boolean,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    alignEnd: Boolean,
) {
    val anchor = remember { MenuAnchor() }
    Box(Modifier.matchParentSize().onPlaced { anchor.coordinates = it })
    GlassMenu(show, entries, anchor::bounds, onDismiss, alignEnd = alignEnd)
}

/** 锚的布局坐标。会话列表、聊天气泡每一行都挂着菜单，位置不写进 Compose 状态（滚动时每帧都会变，写进状态就每帧重组一遍），菜单打开时才算一次窗口坐标。 */
internal class MenuAnchor {
    var coordinates: LayoutCoordinates? = null

    fun bounds(): Rect = coordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero
}

/**
 * 以 [anchorBounds]（打开时取一次的窗口坐标）为锚的玻璃菜单；锚是整个设置行、拿不到 BoxScope 时用，其余用
 * [AnchoredMenu]。没打开过时什么都不建；收起动画放完后调 [onDismissFinished]。
 */
@Composable
internal fun GlassMenu(
    show: Boolean,
    entries: List<MenuEntry>,
    anchorBounds: () -> Rect,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
    alignEnd: Boolean = false,
) {
    val mounted = remember { mutableStateOf(show) }
    if (show) mounted.value = true
    if (!mounted.value) return
    val groups = remember(entries) { entries.filter { it.items.isNotEmpty() } }
    val finished by rememberUpdatedState(onDismissFinished)
    MenuPopup(show, groups, mounted, remember(show) { anchorBounds() }, alignEnd, onDismiss) {
        finished()
    }
}

/** 弹层本身：挂在最外层的 Miuix Scaffold 上，收起动画放完把 [mounted] 清掉、调 [onClosed]。 */
@Composable
private fun MenuPopup(
    show: Boolean,
    groups: List<MenuEntry>,
    mounted: MutableState<Boolean>,
    anchorBounds: Rect,
    alignEnd: Boolean,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    // 二级菜单：childOpen 管弹出收起，child 留到收起动画放完，收起时内容不跟着先没了
    var child by remember { mutableStateOf<MenuItem?>(null) }
    var childOpen by remember { mutableStateOf(false) }
    var childBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(show) {
        if (!show) {
            childOpen = false
            delay(MENU_EXIT_MILLIS)
            child = null
            mounted.value = false
            onClosed()
        }
    }
    // 在这里读：弹层挂在最外层的 Scaffold 上，那里没有页面提供的背景
    val backdrop = LocalPageBackdrop.current
    // 二级菜单的头一行（回到上一层）箭头转向和 Miuix 的级联菜单一样
    val headerArrow = CascadingPopupDefaults.arrowRotation(LocalLayoutDirection.current)
    val pick: (MenuItem) -> Unit = { item ->
        item.onClick?.invoke()
        onDismiss()
    }
    PopupLayout(
        visible = mounted,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableBackHandler = false,
        renderInRootScaffold = true,
    ) {
        // Miuix 的弹层只避开状态栏和导航栏，不避开输入法，但会把菜单收在宿主里：
        // 宿主底下让出键盘的高度，键盘升起时菜单就往上弹，不被盖住（回归：tools/phone-tests/ime-menu.sh）。
        // 用 imePadding 不手算：设置类页面的最外层 Scaffold 已经让出过键盘（MiuixPage），这里只补还没让出的那部分
        val density = LocalDensity.current
        // 图标按钮（alignEnd）和比菜单还宽的锚（整行、整段内容）和它的右边对齐、向左展开，其余（胶囊、底栏按钮）和它水平居中；
        // 贴着屏幕边的由 Miuix 收回窗口里
        val alignment =
            if (alignEnd || anchorBounds.width > with(density) { MenuSizing.maxWidth.toPx() })
                Alignment.End
            else Alignment.CenterHorizontally
        // 弹出动画要看到 show 从假变真：Miuix 的 GlassPopup 用 animateFloatAsState，初值就是它第一次组合时的目标值，而这块内容由最外层
        // Scaffold 在打开之后才组合，直接传 show 就没有弹出动画（收起时有）。所以先按收着组合，下一帧再打开。
        // GlassPopup 的进度改成从 0 起步、或首次组合就打开也有弹出动画后，删掉 entered
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        Box(Modifier.fillMaxSize().imePadding()) {
            GlassPopup(
                show = show && entered,
                onDismissRequest = onDismiss,
                anchorBounds = anchorBounds,
                backdrop = backdrop,
                sizing = MenuSizing,
                horizontalAlignment = alignment,
            ) {
                MenuRows(groups, onPick = pick) { item, rowBounds ->
                    child = item
                    childBounds = rowBounds
                    childOpen = true
                }
            }
            GlassSecondaryPopup(
                show = show && childOpen,
                onDismissRequest = { childOpen = false },
                anchorBounds = childBounds,
                backdrop = backdrop,
                sizing = MenuSizing,
                onDismissFinished = { if (!childOpen) child = null },
            ) {
                child?.let { parent ->
                    GlassPopupItem(
                        text = parent.text,
                        onClick = { childOpen = false },
                        summary = parent.summary,
                        showArrow = true,
                        arrowRotation = { headerArrow },
                    )
                    MenuDivider()
                    parent.children.orEmpty().forEach { item -> MenuRow(item, true) { pick(item) } }
                }
            }
        }
    }
}

/**
 * 一级菜单的各组，组与组之间一条分隔线；带二级菜单的项点了交给 [onOpenChild]（带上这一行此刻的窗口坐标），其余的交给 [onPick]。全部包成一列：Miuix
 * 的玻璃菜单估算高度时只量前 8 个子项，多出来的要滚动才看得到，逐行放的话七项加一条分隔线就把删除挤进了滚动区。
 */
@Composable
private fun MenuRows(
    groups: List<MenuEntry>,
    onPick: (MenuItem) -> Unit,
    onOpenChild: (MenuItem, Rect) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        groups.forEachIndexed { index, entry ->
            if (index > 0) MenuDivider()
            entry.items.forEach { item -> MenuGroupRow(item, entry.enabled, onPick, onOpenChild) }
        }
    }
}

@Composable
private fun MenuGroupRow(
    item: MenuItem,
    groupEnabled: Boolean,
    onPick: (MenuItem) -> Unit,
    onOpenChild: (MenuItem, Rect) -> Unit,
) {
    val hasChildren = !item.children.isNullOrEmpty()
    val anchor = remember { MenuAnchor() }
    MenuRow(
        item,
        groupEnabled,
        if (hasChildren) Modifier.onPlaced { anchor.coordinates = it } else Modifier,
    ) {
        if (hasChildren) onOpenChild(item, anchor.bounds()) else onPick(item)
    }
}

@Composable
private fun MenuRow(
    item: MenuItem,
    groupEnabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassPopupItem(
        text = item.text,
        onClick = onClick,
        modifier = modifier,
        icon = item.icon,
        summary = item.summary,
        enabled = groupEnabled && item.enabled,
        selected = item.selected,
        showArrow = !item.children.isNullOrEmpty(),
        contentColor =
            if (item.alert) MiuixTheme.colorScheme.error
            else MiuixTheme.colorScheme.onSurfaceContainer,
    )
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier =
            Modifier.padding(
                horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                vertical = GlassPopupDefaults.DividerPaddingVertical,
            ),
        thickness = GlassPopupDefaults.DividerThickness,
    )
}

/** 菜单最高 600dp（Miuix 默认 416dp）：Zeta 最长的菜单（会话的九项、模型清单）放得下就整个展开，不用滚；屏幕不够时弹层自己收在窗口里。 */
private val MenuSizing = GlassPopupSizing(maxHeight = 600.dp)

/** 收起动画的时长（照 Eta 的 GlassSpinnerPreference），放完才把弹层从 Scaffold 上撤下。 */
private const val MENU_EXIT_MILLIS = 420L

/** 顶栏右边的“更多”：在玻璃顶栏里是玻璃圆钮，[entries] 每组之间有分隔线，点一项就收起。 */
@Composable
fun TopBarMoreMenu(entries: List<MenuEntry>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconActionButton(
            icon = MiuixIcons.More,
            contentDescription = Strings.componentsMore(),
            holdDownState = expanded,
            onClick = { expanded = true },
        )
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}

/** 列表行里的“更多”：平的图标按钮，菜单展开时按钮保持按下的样子，点一项就收起；按钮和菜单在同一个 Box 里，菜单以按钮为锚。 */
@Composable
fun RowMoreMenu(
    contentDescription: String,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    IconMenuButton(MiuixIcons.More, contentDescription, entries, modifier, iconSize = iconSize)
}

/**
 * 平的图标按钮加以它为锚的菜单（[RowMoreMenu]、侧栏的排序按钮）：菜单展开时按钮保持按下的样子，点一项就收起。这类按钮都在行尾， 菜单和按钮右边对齐、向左展开，不伸出所在的面板。
 * [stateDescription] 是读屏在名字之后念的状态（比如当前选的是哪一项），没有就不念。
 */
@Composable
fun IconMenuButton(
    icon: ImageVector,
    contentDescription: String,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
    size: Dp = IconButtonDefaults.MinWidth,
    iconSize: Dp = 24.dp,
    stateDescription: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FlatIconButton(
            icon = icon,
            contentDescription = contentDescription,
            size = size,
            iconSize = iconSize,
            modifier =
                if (stateDescription.isEmpty()) Modifier
                else Modifier.semantics { this.stateDescription = stateDescription },
            holdDownState = expanded,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            onClick = { expanded = true },
        )
        anchoredMenu(
            show = expanded,
            entries = entries,
            onDismiss = { expanded = false },
            alignEnd = true,
        )
    }
}
