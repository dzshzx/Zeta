package com.dzshzx.zeta.ui.chat

import com.dzshzx.zeta.ui.components.coui2.AnchoredMenu
import com.dzshzx.zeta.ui.components.coui2.menuItem
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.SlashCommand
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconChip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme

internal val PermissionModes = listOf(
    "default" to "逐项确认",
    "acceptEdits" to "自动接受编辑",
    "plan" to "计划模式",
    "auto" to "自动判断",
    "bypassPermissions" to "完全放行",
)

internal fun permissionModeLabel(mode: String?): String =
    PermissionModes.firstOrNull { it.first == mode }?.second ?: mode ?: "默认权限"

/** 输入栏一带的面板（输入卡片、它上方的几种条、说明卡）的圆角。 */
internal val ChatPanelCorner = COUIRoundCorner.XXL

/** 输入栏上方几种条的圆角。 */
internal val ChatStripCorner = COUIRoundCorner.XL

/**
 * 输入栏一带的面板：页面底色上一层浅灰（COUI container4 叠在页面底色上，不透明，阴影不会透出来），同色细边，平滑圆角。
 * 输入卡片、查找栏旁的条、排队条、任务清单、编辑提示条、后台屏幕条、“会话在别处运行”的说明卡都用它。
 */
@Composable
internal fun Modifier.chatPanel(shape: Shape): Modifier {
    val colors = COUITheme.colorScheme
    return clip(shape)
        .background(colors.container4.compositeOver(colors.background), shape)
        .border(0.5.dp, colors.container4, shape)
}

/**
 * 输入卡片：圆角面板里是输入框，下面一行是附件键、权限模式按钮、思考胶囊，右边是用量按钮和圆形发送键。
 * 发送键和用量按钮尺寸固定，窄屏放不下时截断的是思考胶囊。
 */
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
    /** 引用 Linux 里的文件夹（从手机这边选）。 */
    onPickFolder: () -> Unit,
    /** 直接输入 Linux 里的路径。 */
    onEnterPath: () -> Unit,
    /** 打开常用指令（存下来的提问），点一条填进输入框。 */
    onOpenPrompts: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onRemoveFile: (FileUpload) -> Unit,
    /** 用户开始输入斜杠命令：趁这时把进程起好，拿到最新的命令清单。 */
    onSlashTyped: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** 权限模式按钮后面的胶囊（思考强度）：分到剩下的宽度，放不下时由它截断。 */
    extraChips: @Composable () -> Unit = {},
    /** 发送键左边的小部件（用量按钮），按自己的尺寸量，不被挤小。 */
    trailing: @Composable () -> Unit = {},
) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    val shape = remember { SquircleShape(ChatPanelCorner) }
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
    val textStyle = COUITheme.textStyles.bodyXL.copy(
        color = colors.labelPrimary,
        lineHeight = 22.sp,
        lineHeightStyle = EditorialLineHeightStyle,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(shape = shape, shadow = Shadow(radius = 16.dp, color = Color.Black, alpha = if (dark) 0.22f else 0.045f))
            .chatPanel(shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (suggestions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 232.dp).padding(bottom = 4.dp)) {
                items(suggestions, key = { it.name }) { command ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(COUIRoundCorner.M))
                            .clickable { state.setTextAndPlaceCursorAtEnd("/${command.name} ") }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "/${command.name}",
                                style = COUITheme.textStyles.buttonM,
                                color = colors.primary,
                                maxLines = 1,
                            )
                            if (command.argumentHint.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = command.argumentHint,
                                    style = COUITheme.textStyles.bodyXS,
                                    color = colors.labelSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (command.description.isNotBlank()) {
                            Text(
                                text = command.description,
                                style = COUITheme.textStyles.bodyXS,
                                color = colors.labelSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 6.dp), color = colors.divider)
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
                        folder = AttachedPaths.isFolder(file.path.orEmpty()),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除",
                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onRemoveFile(file) }.padding(4.dp),
                            tint = colors.labelSecondary,
                        )
                    }
                }
                attachments.forEach { attachment ->
                    Box {
                        Image(
                            bitmap = attachment.preview,
                            contentDescription = "图片附件",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(COUIRoundCorner.M)),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(colors.mask)
                                .clickable { onRemoveAttachment(attachment) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "移除", modifier = Modifier.size(13.dp), tint = colors.labelOnColor)
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
                    color = colors.hintNeutral,
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
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                IconChip(onClick = { attachMenu = true }, enabled = enabled) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加附件")
                }
                val attachEntries = listOf(
                    DropdownEntry(
                        items = listOf(
                            menuItem("图片", Icons.Rounded.Image, onPickImages),
                            menuItem("文件", Icons.Rounded.AttachFile, onPickFiles),
                            menuItem("文件夹", Icons.Rounded.Folder, onPickFolder),
                            menuItem("输入路径", Icons.Rounded.Edit, onEnterPath),
                        ),
                    ),
                    DropdownEntry(items = listOf(menuItem("常用指令", Icons.Rounded.BookmarkBorder, onOpenPrompts))),
                )
                AnchoredMenu(show = attachMenu, entries = attachEntries, onDismiss = { attachMenu = false })
            }
            Spacer(Modifier.width(8.dp))
            PermissionModeChip(mode = permissionMode, onChange = onPermissionModeChange)
            // 思考胶囊分到的是别的都量完后剩下的宽度，窄屏放不下时截断的是它；右边的用量按钮和发送键先量，不会被挤小
            Row(modifier = Modifier.weight(1f).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                extraChips()
            }
            trailing()
            val showStop = busy && !canSend
            val buttonColor by animateColorAsState(
                targetValue = when {
                    showStop -> colors.labelPrimary
                    canSend -> colors.primary
                    else -> colors.container8
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
                                stop -> colors.background
                                canSend -> colors.onPrimary
                                else -> colors.labelTertiary
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 权限模式按钮：只有一面盾牌，颜色看模式（放得越开越醒目），模式名在读屏说明和点开的单选菜单里。
 * 逐项确认和没指定时是中性的灰，计划模式青色，自动接受编辑紫色，自动判断主题蓝，完全放行红色。
 */
@Composable
internal fun PermissionModeChip(mode: String?, onChange: (String) -> Unit) {
    val colors = COUITheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val tint = when (mode) {
        "plan" -> colors.cyan
        "acceptEdits" -> colors.purple
        "auto" -> colors.primaryText
        "bypassPermissions" -> colors.error
        else -> null
    }
    Box {
        IconChip(
            onClick = { expanded = true },
            colors = if (tint == null) {
                ChipDefaults.chipColors(contentColor = colors.labelSecondary)
            } else {
                ChipDefaults.chipColors(containerColor = tint.copy(alpha = if (colors.isDark) 0.25f else 0.15f), contentColor = tint)
            },
        ) {
            Icon(imageVector = Icons.Rounded.Shield, contentDescription = "权限模式：${permissionModeLabel(mode)}")
        }
        val entries = listOf(
            DropdownEntry(
                items = PermissionModes.map { (value, label) ->
                    DropdownItem(text = label, selected = value == mode, onClick = { onChange(value) })
                },
            ),
        )
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}

