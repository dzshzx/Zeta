package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.i18n.Strings
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 是否在玻璃顶栏的按钮槽里：[MiuixPageTopBar] 给返回键和 actions 提供 true，里面的 [IconActionButton] 画成玻璃圆钮。 */
val LocalInGlassTopBar = staticCompositionLocalOf { false }

/** 玻璃顶栏里图标的大小（照 Eta 的 TopBarButtons.kt）。 */
private val GlassTopBarIconSize = 28.dp

/**
 * 只放一个图标的按钮（顶栏的新建、刷新，侧栏的圆钮，回到底部）；图标按钮都从它建，不在页面里各自写一份。 在玻璃顶栏里（[LocalInGlassTopBar]）画成 Miuix
 * 的玻璃圆钮，别处是 Miuix 的 IconButton，参数照它原样转发；[tint] 是能点时图标的颜色。
 *
 * 不能点时图标换成禁用色，并补上按钮角色和不可用状态：Miuix 的 IconButton 不能点时不加 clickable，读屏只看到一张图片（`MiuixGapsTest`
 * 断言这个缺口还在，上游修好后删掉这段 semantics）。
 */
@Composable
fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    iconModifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    onClick: () -> Unit,
) {
    val semanticModifier =
        if (enabled) {
            modifier
        } else {
            modifier.semantics(mergeDescendants = true) {
                role = Role.Button
                disabled()
            }
        }
    val iconTint = if (enabled) tint else MiuixTheme.colorScheme.disabledOnSurface
    if (LocalInGlassTopBar.current) {
        GlassIconButton(onClick = { if (enabled) onClick() }, modifier = semanticModifier) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(GlassTopBarIconSize),
                tint = iconTint,
            )
        }
        return
    }
    IconButton(
        onClick = onClick,
        modifier = semanticModifier,
        enabled = enabled,
        holdDownState = holdDownState,
        backgroundColor = backgroundColor,
        cornerRadius = cornerRadius,
        minHeight = minHeight,
        minWidth = minWidth,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = iconModifier,
            tint = iconTint,
        )
    }
}

/** 平的图标按钮（不画成玻璃圆钮）：[size] 见方的圆，图标 [iconSize]。列表行、提示条、查找栏、消息下方的小按钮用它。 */
@Composable
fun FlatIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalInGlassTopBar provides false) {
        IconActionButton(
            icon = icon,
            contentDescription = contentDescription,
            modifier = modifier,
            enabled = enabled,
            holdDownState = holdDownState,
            backgroundColor = backgroundColor,
            minWidth = size,
            minHeight = size,
            cornerRadius = size / 2,
            iconModifier = Modifier.size(iconSize),
            tint = tint,
            onClick = onClick,
        )
    }
}

/** 复制后对勾停留多久再变回复制图标。 */
private const val CopiedFeedbackMillis = 1_500L

/** 复制键（回答下方、代码块标题栏）：点了把 [text] 放进剪贴板，图标换成主题色的对勾，过一会儿复原。 */
@Composable
fun CopyIconButton(
    text: String,
    size: Dp,
    iconSize: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = Strings.commonCopy(),
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedFeedbackMillis)
            copied = false
        }
    }
    FlatIconButton(
        icon = if (copied) MiuixIcons.Ok else MiuixIcons.Copy,
        contentDescription = if (copied) Strings.commonCopied() else contentDescription,
        size = size,
        iconSize = iconSize,
        modifier = modifier,
        tint = if (copied) MiuixTheme.colorScheme.primary else tint,
    ) {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }
}
