package com.dzshzx.zeta.ui.chat

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
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.SlashCommand
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
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
    files: List<FileUpload>,
    commands: List<SlashCommand>,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onRemoveFile: (FileUpload) -> Unit,
    /** 用户开始输入斜杠命令：趁这时把进程起好，拿到最新的命令清单。 */
    onSlashTyped: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** 权限胶囊后面的胶囊（思考强度）。 */
    extraChips: @Composable () -> Unit = {},
    /** 发送键左边的小部件（上下文用量）。 */
    trailing: @Composable () -> Unit = {},
) {
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val shape = remember { CouiShape(24.dp, 2.3f) }
    val canSend = enabled && files.none { it.uploading } &&
        (state.text.isNotBlank() || attachments.isNotEmpty() || files.any { it.path != null })
    // 只打了“/名字”还没打空格时，提示可用的斜杠命令
    val typed = state.text.toString()
    val slash = if (typed.startsWith("/") && typed.none { it.isWhitespace() }) typed.drop(1).lowercase() else null
    val suggestions = remember(slash, commands) {
        if (slash == null) {
            emptyList()
        } else {
            val visible = commands.filter { !it.name.startsWith("__") }
            visible.filter { it.name.lowercase().startsWith(slash) } +
                visible.filter { !it.name.lowercase().startsWith(slash) && it.name.lowercase().contains(slash) }
        }
    }
    LaunchedEffect(slash != null) { if (slash != null) onSlashTyped() }
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
        if (suggestions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 232.dp).padding(bottom = 4.dp)) {
                items(suggestions, key = { it.name }) { command ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { state.setTextAndPlaceCursorAtEnd("/${command.name} ") }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "/${command.name}",
                                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                color = colors.primary,
                                maxLines = 1,
                            )
                            if (command.argumentHint.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = command.argumentHint,
                                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                                    color = colors.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (command.description.isNotBlank()) {
                            Text(
                                text = command.description,
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(0.5.dp).background(colors.onSurface.copy(alpha = 0.08f)))
            Spacer(Modifier.height(4.dp))
        }
        if (attachments.isNotEmpty() || files.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                files.forEach { file ->
                    FileChip(
                        name = when {
                            file.failed -> "复制失败 · ${file.name}"
                            file.uploading -> "正在复制 · ${file.name}"
                            else -> file.name
                        },
                        modifier = Modifier.widthIn(max = 220.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除",
                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onRemoveFile(file) }.padding(4.dp),
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }
                }
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
                    text = if (busy) "Claude 正在工作，可以接着发" else "交给 Claude 去完成",
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
            val attachAnchor = rememberCouiMenuAnchor()
            var attachMenu by remember { mutableStateOf(false) }
            val attachItems = remember {
                listOf(
                    CouiMenuItem(value = 0, text = "图片", icon = Icons.Rounded.Image),
                    CouiMenuItem(value = 1, text = "文件", icon = Icons.Rounded.AttachFile),
                )
            }
            Box {
                Box(
                    modifier = Modifier
                        .couiMenuAnchor(attachAnchor)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(colors.onSurface.copy(alpha = if (dark) 0.08f else 0.05f), CircleShape)
                        .clickable(enabled = enabled) { attachAnchor.open { attachMenu = true } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加图片或文件", modifier = Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = 0.72f))
                }
                Menu(
                    expanded = attachMenu,
                    onDismissRequest = { attachMenu = false },
                    items = attachItems,
                    onItemClick = {
                        attachMenu = false
                        if (it.value == 0) onPickImages() else onPickFiles()
                    },
                    anchor = attachAnchor,
                )
            }
            Spacer(Modifier.width(8.dp))
            PermissionModeChip(mode = permissionMode, onChange = onPermissionModeChange)
            extraChips()
            Spacer(Modifier.weight(1f))
            trailing()
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
