package io.github.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.layout.ContentScale
import io.github.dzshzx.zeta.core.Attachment
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import io.github.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val PermissionModes = listOf(
    "default" to "逐项确认",
    "acceptEdits" to "自动接受编辑",
    "plan" to "计划模式",
    "auto" to "自动判断",
    "bypassPermissions" to "完全放行",
)

internal fun permissionModeLabel(mode: String?): String =
    PermissionModes.firstOrNull { it.first == mode }?.second ?: mode ?: "默认权限"

/** 输入卡片：样式与 Eta 的 COUI 输入栏一致（24dp 平滑圆角、浅灰底、细边框、右下角圆形发送键）。 */
@Composable
internal fun Composer(
    state: TextFieldState,
    busy: Boolean,
    enabled: Boolean,
    permissionMode: String?,
    onPermissionModeChange: (String) -> Unit,
    attachments: List<Attachment>,
    onPickImages: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val shape = remember { CouiShape(24.dp, 2.3f) }
    val canSend = enabled && (state.text.isNotBlank() || attachments.isNotEmpty())
    val textStyle = MiuixTheme.textStyles.body1.copy(
        color = colors.onSurface,
        lineHeight = 22.sp,
        lineHeightStyle = EditorialLineHeightStyle,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(shape = shape, shadow = Shadow(radius = 16.dp, color = Color.Black, alpha = if (dark) 0.22f else 0.045f))
            .clip(shape)
            .background(if (dark) Color(0xFF1C1E22) else Color(0xFFF6F7F9), shape)
            .border(0.5.dp, if (dark) Color(0x20FFFFFF) else Color(0x0E000000), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    Box {
                        Image(
                            bitmap = attachment.preview,
                            contentDescription = "图片附件",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0x99000000))
                                .clickable { onRemoveAttachment(attachment) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "移除", modifier = Modifier.size(13.dp), tint = Color.White)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (state.text.isEmpty()) {
                Text(
                    text = if (busy) "Claude 正在工作…" else "交给 Claude 去完成",
                    style = textStyle,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.onSurface.copy(alpha = if (dark) 0.08f else 0.05f), CircleShape)
                    .clickable(enabled = enabled, onClick = onPickImages),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加图片", modifier = Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = 0.72f))
            }
            Spacer(Modifier.width(8.dp))
            PermissionModeChip(mode = permissionMode, onChange = onPermissionModeChange)
            Spacer(Modifier.weight(1f))
            val showStop = busy && !canSend
            val ink = colors.onSurface
            val buttonColor by animateColorAsState(
                targetValue = when {
                    showStop -> colors.onSurface
                    canSend -> colors.primary
                    else -> ink.copy(alpha = if (dark) 0.08f else 0.06f)
                },
                animationSpec = tween(160),
                label = "send-color",
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = showStop || canSend) {
                        if (showStop) {
                            onStop()
                        } else {
                            val text = state.text.toString()
                            state.clearText()
                            onSend(text)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(buttonColor), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = showStop,
                        transitionSpec = {
                            (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                .togetherWith(fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.72f))
                        },
                        label = "send-stop",
                    ) { stop ->
                        Icon(
                            imageVector = if (stop) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (stop) "停止" else "发送",
                            modifier = Modifier.size(if (stop) 12.dp else 17.dp),
                            tint = when {
                                stop -> colors.surface
                                canSend -> colors.onPrimary
                                else -> ink.copy(alpha = if (dark) 0.28f else 0.24f)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 权限模式胶囊，位置和样式对应 Eta 输入栏里的“思考”胶囊。 */
@Composable
private fun PermissionModeChip(mode: String?, onChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val anchor = rememberCouiMenuAnchor()
    var expanded by remember { mutableStateOf(false) }
    val items = remember(mode) { PermissionModes.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first == mode) } }
    Box {
        Row(
            modifier = Modifier
                .couiMenuAnchor(anchor)
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.10f), CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { anchor.open { expanded = true } }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(14.5.dp), tint = colors.primary)
            Spacer(Modifier.width(5.dp))
            Text(text = permissionModeLabel(mode), style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp), color = colors.primary)
        }
        Menu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = items,
            onItemClick = {
                expanded = false
                onChange(it.value)
            },
            anchor = anchor,
        )
    }
}
