package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.IconButtonDefaults
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay

/**
 * 只放一个图标的按钮（顶栏的新建、刷新，侧栏的圆钮，回到底部）；图标按钮都从它建，不在页面里各自写一份。 按钮参数照 COUI 的 IconButton 原样转发，[tint]
 * 是能点时图标的颜色。
 *
 * 补的是 COUI 2.0.0 IconButton 的两处缺口：IconButtonContainer 不给内容提供 LocalContentColor，不能点时图标颜色不变，
 * 这里换成禁用色；不能点时它也不加 clickable，读屏只看到一张图片，这里补上按钮角色和不可用状态。 上游修好后删掉 tint 换色和这段 semantics。
 */
@Composable
internal fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    material: Boolean = LocalCOUIMaterialEffectEnabled.current,
    backgroundColor: Color = Color.Unspecified,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    iconModifier: Modifier = Modifier,
    tint: Color = COUITheme.colorScheme.labelPrimary,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            if (enabled) {
                modifier
            } else {
                modifier.semantics(mergeDescendants = true) {
                    role = Role.Button
                    disabled()
                }
            },
        enabled = enabled,
        holdDownState = holdDownState,
        material = material,
        backgroundColor = backgroundColor,
        cornerRadius = cornerRadius,
        minHeight = minHeight,
        minWidth = minWidth,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = iconModifier,
            tint = if (enabled) tint else COUITheme.colorScheme.disabledNeutral,
        )
    }
}

/** 平的图标按钮（不带顶栏按钮的材质）：[size] 见方的圆，图标 [iconSize]。列表行、提示条、查找栏、消息下方的小按钮用它。 */
@Composable
internal fun FlatIconButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    tint: Color = COUITheme.colorScheme.labelPrimary,
    onClick: () -> Unit,
) {
    IconActionButton(
        icon = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        enabled = enabled,
        holdDownState = holdDownState,
        material = false,
        backgroundColor = backgroundColor,
        minWidth = size,
        minHeight = size,
        cornerRadius = size / 2,
        iconModifier = Modifier.size(iconSize),
        tint = tint,
        onClick = onClick,
    )
}

/** 复制后对勾停留多久再变回复制图标。 */
private const val CopiedFeedbackMillis = 1_500L

/** 复制键（回答下方、代码块标题栏）：点了把 [text] 放进剪贴板，图标换成主题色的对勾，过一会儿复原。 */
@Composable
internal fun CopyIconButton(
    text: String,
    size: Dp,
    iconSize: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = "复制",
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
        icon = if (copied) COUIIcons.Ok else COUIIcons.Copy,
        contentDescription = if (copied) "已复制" else contentDescription,
        size = size,
        iconSize = iconSize,
        modifier = modifier,
        tint = if (copied) COUITheme.colorScheme.primary else tint,
    ) {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }
}
