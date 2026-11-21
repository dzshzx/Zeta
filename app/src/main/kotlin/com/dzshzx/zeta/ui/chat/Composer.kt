package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.SlashCommand
import com.dzshzx.zeta.core.voice.SpeechInput
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Favorites
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.icon.extended.Mic
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * 输入卡片：圆角面板里是输入框，下面是底栏：左边附件键和 [tools]，右边 [status] 和圆形发送键，空出来的地方在中间。
 * 底栏只放定宽的图标按钮（[BarButton]、[BarMenuButton]），间距在这里统一给，窄屏也不会挤。
 */
@Composable
internal fun Composer(
    state: TextFieldState,
    busy: Boolean,
    enabled: Boolean,
    attachments: List<Attachment>,
    files: List<FileUpload>,
    commands: List<SlashCommand>,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    /** 引用 Linux 里的文件夹（从手机这边选）；对话在远程主机上时没有这一项（null）。 */
    onPickFolder: (() -> Unit)?,
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
    /** 附件键后面的底栏按钮（权限模式、思考强度）。 */
    tools: @Composable () -> Unit = {},
    /** 发送键左边的底栏按钮（额度提醒、上下文圆环）。 */
    status: @Composable () -> Unit = {},
    /** 按住说话（见 HoldToTalk.kt）；手机上没有识别服务时为 null。 */
    voice: HoldToTalk? = null,
    /** 输入框的焦点：聊天页在列表上拖动时用它调起输入法（见 SwipeKeyboard.kt）。 */
    focus: FocusRequester = remember { FocusRequester() },
) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    val shape = remember { SquircleShape(ChatPanelCorner) }
    val canSend = enabled && files.none { it.uploading } &&
        (state.text.isNotBlank() || attachments.isNotEmpty() || files.any { it.path != null })
    val talk by (voice?.input?.state ?: IdleTalk).collectAsState()
    val talking = talk.active
    val cancelling = talking && voice?.cancelling == true
    // 说话时输入卡片描一圈主题色，滑进取消区换成红色
    val talkBorder by animateColorAsState(
        targetValue = when {
            cancelling -> colors.error.copy(alpha = 0.6f)
            talking -> colors.primary.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "talk-border",
    )
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
            .border(1.dp, talkBorder, shape)
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
                            COUIIcons.Close,
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
                            Icon(COUIIcons.Close, contentDescription = "移除", modifier = Modifier.size(13.dp), tint = colors.labelOnColor)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp).padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            when {
                // 说话时输入框是空的：识别出的字实时显示在这里，松手后整段发出去
                talking -> Text(
                    text = talk.text.ifEmpty { if (talk.phase == SpeechInput.Phase.Finishing) "正在识别…" else "正在听…" },
                    style = textStyle,
                    color = if (talk.text.isEmpty()) colors.primaryText else colors.labelPrimary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                state.text.isEmpty() -> Text(
                    text = if (busy) "Claude 正在工作，可以接着发" else "交给 Claude 去完成",
                    style = textStyle,
                    color = colors.hintNeutral,
                )
            }
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                enabled = enabled,
                textStyle = textStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val attachEntries = listOf(
                DropdownEntry(
                    items = listOfNotNull(
                        menuItem("图片", COUIIcons.Image, onPickImages),
                        menuItem("文件", ZetaIcons.Paperclip, onPickFiles),
                        onPickFolder?.let { menuItem("文件夹", COUIIcons.Folder, it) },
                        menuItem("输入路径", COUIIcons.Edit, onEnterPath),
                    ),
                ),
                DropdownEntry(items = listOf(menuItem("常用指令", COUIIcons.Favorites, onOpenPrompts))),
            )
            if (talking) {
                // 说话时底栏只留提示和麦克风键
                Text(
                    text = when {
                        talk.phase == SpeechInput.Phase.Finishing -> "松开了，正在识别"
                        cancelling -> "松开取消"
                        else -> "松开发送，上滑取消"
                    },
                    style = COUITheme.textStyles.bodyS,
                    color = if (cancelling) colors.error else colors.labelSecondary,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                    maxLines = 1,
                )
            } else {
                BarMenuButton(description = "添加附件", entries = attachEntries, enabled = enabled) {
                    Icon(COUIIcons.Add, contentDescription = null)
                }
                tools()
                Spacer(Modifier.weight(1f))
                status()
            }
            val showStop = busy && !canSend
            // 没东西可发、也没在跑时（或正说着话），发送键换成按住说话的麦克风
            val mic = voice != null && enabled && (talking || (!showStop && !canSend))
            val buttonColor by animateColorAsState(
                targetValue = when {
                    cancelling -> colors.error
                    talking -> colors.primary
                    showStop -> colors.labelPrimary
                    canSend -> colors.primary
                    else -> colors.container8
                },
                animationSpec = tween(160),
                label = "send-color",
            )
            val haptics = LocalHapticFeedback.current
            val level by animateFloatAsState(if (talk.phase == SpeechInput.Phase.Listening) talk.level else 0f, tween(90), label = "talk-level")
            val grow by animateFloatAsState(if (talking) 1.12f else 1f, tween(160), label = "talk-grow")
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .then(
                        if (mic && voice != null) {
                            Modifier
                                .semantics { contentDescription = "按住说话" }
                                .holdToTalk(voice, haptics) { text -> onSend(text) }
                        } else {
                            Modifier.clip(CircleShape).clickable(enabled = showStop || canSend) {
                                if (showStop) {
                                    onStop()
                                } else {
                                    val text = state.text.toString()
                                    state.clearText()
                                    onSend(text)
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 说话时按钮后面一圈随音量放大的光晕
                if (talking) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                val scale = 1f + 0.45f * level
                                scaleX = scale
                                scaleY = scale
                            }
                            .background(buttonColor.copy(alpha = 0.22f), CircleShape),
                    )
                }
                Box(
                    modifier = Modifier.size(32.dp).graphicsLayer { scaleX = grow; scaleY = grow }.clip(CircleShape).background(buttonColor),
                    contentAlignment = Alignment.Center,
                ) {
                    val face = when {
                        mic -> SendFace.Mic
                        showStop -> SendFace.Stop
                        else -> SendFace.Send
                    }
                    AnimatedContent(
                        targetState = face,
                        transitionSpec = {
                            (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                .togetherWith(fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.72f))
                        },
                        label = "send-stop",
                    ) { shown ->
                        Icon(
                            imageVector = when (shown) {
                                SendFace.Mic -> COUIIcons.Mic
                                SendFace.Stop -> ZetaIcons.Stop
                                SendFace.Send -> ZetaIcons.ArrowUp
                            },
                            contentDescription = if (shown == SendFace.Mic) null else if (shown == SendFace.Stop) "停止" else "发送",
                            modifier = Modifier.size(
                                when (shown) {
                                    SendFace.Mic -> 17.dp
                                    SendFace.Stop -> 12.dp
                                    SendFace.Send -> 17.dp
                                },
                            ),
                            tint = when {
                                shown == SendFace.Mic && talking -> colors.onPrimary
                                shown == SendFace.Mic -> colors.labelSecondary
                                shown == SendFace.Stop -> colors.background
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

/** 发送键上显示什么。 */
private enum class SendFace { Send, Stop, Mic }

/** 没有按住说话时的识别状态（一直空闲）。 */
private val IdleTalk: StateFlow<SpeechInput.State> = MutableStateFlow(SpeechInput.State())

/**
 * 权限模式按钮：一面盾牌，里面的字母和颜色看模式，颜色固定、不跟强调色变（选红色强调色时也分得清「完全放行」）。
 * 逐项确认和没指定时是灰色的 M，计划模式青色的 P，自动接受编辑紫色的 E，自动判断蓝色的 A，完全放行红色的感叹号。
 * 模式名在读屏说明、长按提示和点开的单选菜单里。
 */
@Composable
internal fun PermissionModeChip(mode: String?, onChange: (String) -> Unit) {
    val colors = COUITheme.colorScheme
    val (icon, tone) = when (mode) {
        "auto" -> ZetaIcons.ShieldA to colors.blue
        "plan" -> ZetaIcons.ShieldP to colors.cyan
        "acceptEdits" -> ZetaIcons.ShieldE to colors.purple
        "bypassPermissions" -> ZetaIcons.ShieldAlert to colors.error
        else -> ZetaIcons.ShieldM to null
    }
    val entries = listOf(
        DropdownEntry(
            items = PermissionModes.map { (value, label) ->
                DropdownItem(text = label, selected = value == mode, onClick = { onChange(value) })
            },
        ),
    )
    BarMenuButton(description = "权限模式：${permissionModeLabel(mode)}", entries = entries, colors = barChipColors(tone)) {
        Icon(imageVector = icon, contentDescription = null)
    }
}
