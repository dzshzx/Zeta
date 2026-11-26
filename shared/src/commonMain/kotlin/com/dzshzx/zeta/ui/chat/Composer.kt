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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.SlashCommand
import com.dzshzx.zeta.core.voice.SpeechPhase
import com.dzshzx.zeta.core.voice.SpeechState
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.components.accentBlue
import com.dzshzx.zeta.ui.components.accentCyan
import com.dzshzx.zeta.ui.components.accentPurple
import com.dzshzx.zeta.ui.components.fill4
import com.dzshzx.zeta.ui.components.fill8
import com.dzshzx.zeta.ui.components.isDark
import com.dzshzx.zeta.ui.components.miuix.FlatIconButton
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.SquircleShape
import com.dzshzx.zeta.ui.components.miuix.ZetaCorner
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 权限模式的值和按界面语言的名字；每次取时出字，界面语言变了也对。 */
val PermissionModes: List<Pair<String, String>>
    get() =
        listOf(
            "default" to Strings.composerModeDefault(),
            "acceptEdits" to Strings.composerModeAcceptEdits(),
            "plan" to Strings.composerModePlan(),
            "auto" to Strings.composerModeAuto(),
            "bypassPermissions" to Strings.composerModeBypass(),
        )

fun permissionModeLabel(mode: String?): String =
    PermissionModes.firstOrNull { it.first == mode }?.second ?: mode ?: Strings.composerModeUnset()

/** 输入栏一带的面板（输入卡片、它上方的几种条、说明卡）的圆角。 */
val ChatPanelCorner = ZetaCorner.XXL

/** 输入栏上方几种条的圆角。 */
val ChatStripCorner = ZetaCorner.XL

/**
 * 输入栏一带的面板：页面底色上一层浅灰（fill4 叠在页面底色上，不透明，阴影不会透出来），同色细边，平滑圆角。
 * 输入卡片、查找栏旁的条、排队条、任务清单、编辑提示条、后台屏幕条、“会话在别处运行”的说明卡都用它。
 */
@Composable
fun Modifier.chatPanel(shape: Shape): Modifier {
    val colors = MiuixTheme.colorScheme
    return clip(shape)
        .background(colors.fill4.compositeOver(colors.background), shape)
        .border(0.5.dp, colors.fill4, shape)
}

/**
 * 输入卡片：圆角面板里是输入框，下面是底栏：左边附件键和 [tools]，右边 [status] 和圆形发送键，空出来的地方在中间。
 * 底栏只放定宽的图标按钮（[BarButton]、[BarMenuButton]），间距在这里统一给，窄屏也不会挤。
 */
@Composable
fun Composer(
    state: TextFieldState,
    busy: Boolean,
    enabled: Boolean,
    attachments: List<Attachment>,
    files: List<FileUpload>,
    commands: List<SlashCommand>,
    configCommandsEnabled: Boolean = false,
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
    /** 附件键后面的底栏按钮（权限模式、思考强度、上下文圆环）。 */
    tools: @Composable () -> Unit = {},
    /** 发送键左边的底栏按钮（主机负载）。 */
    status: @Composable () -> Unit = {},
    /** 按住说话（见 HoldToTalk.kt）；手机上没有识别服务时为 null。 */
    voice: HoldToTalk? = null,
    /** 挂在输入框上的焦点，调用方用它把焦点给输入框。 */
    focus: FocusRequester = remember { FocusRequester() },
) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.isDark
    val shape = remember { SquircleShape(ChatPanelCorner) }
    val canSend = hasSomethingToSend(enabled, state.text, attachments, files)
    val talk by (voice?.input?.state ?: IdleTalk).collectAsState()
    val talking = talk.active
    val cancelling = talking && voice?.cancelling == true
    // 说话时输入卡片描一圈主题色，滑进取消区换成红色
    val talkBorder by
        animateColorAsState(
            targetValue = talkBorderTarget(talking, cancelling),
            animationSpec = tween(160),
            label = "talk-border",
        )
    val textStyle =
        MiuixTheme.textStyles.body1.copy(
            color = colors.onBackground,
            lineHeight = 22.sp,
            lineHeightStyle = EditorialLineHeightStyle,
        )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .dropShadow(
                    shape = shape,
                    shadow =
                        Shadow(
                            radius = 16.dp,
                            color = Color.Black,
                            alpha = if (dark) 0.22f else 0.045f,
                        ),
                )
                .chatPanel(shape)
                .border(1.dp, talkBorder, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        ComposerSlash(state, commands, configCommandsEnabled, onSlashTyped)
        if (attachments.isNotEmpty() || files.isNotEmpty()) {
            ComposerAttachments(attachments, files, onRemoveAttachment, onRemoveFile)
        }
        ComposerField(state, talk, busy, enabled, textStyle, focus)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (talking) {
                // 说话时底栏只留提示和麦克风键
                TalkHint(talk, cancelling, Modifier.weight(1f).padding(start = 6.dp))
            } else {
                val attachEntries =
                    attachMenu(onPickImages, onPickFiles, onPickFolder, onEnterPath, onOpenPrompts)
                BarMenuButton(
                    description = Strings.composerAttach(),
                    entries = attachEntries,
                    enabled = enabled,
                ) {
                    Icon(ZetaIcons.Plus, contentDescription = null)
                }
                tools()
                Spacer(Modifier.weight(1f))
                status()
            }
            SendButton(
                state = state,
                talk = talk,
                cancelling = cancelling,
                busy = busy,
                canSend = canSend,
                enabled = enabled,
                voice = voice,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

/** 输入卡片描边的目标颜色：说话时是主题色，滑进取消区换成红色，平时透明。 */
@Composable
private fun talkBorderTarget(talking: Boolean, cancelling: Boolean): Color {
    val colors = MiuixTheme.colorScheme
    return when {
        cancelling -> colors.error.copy(alpha = 0.6f)
        talking -> colors.primary.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
}

/** 有没有东西可发：输入栏可用、没有文件在复制，且有字、有图片或有传好的文件。 */
private fun hasSomethingToSend(
    enabled: Boolean,
    text: CharSequence,
    attachments: List<Attachment>,
    files: List<FileUpload>,
): Boolean =
    enabled &&
        files.none { it.uploading } &&
        (text.isNotBlank() || attachments.isNotEmpty() || files.any { it.path != null })

/** 光标前那个词是“/名字”（在开头或空白后面，见 [SlashToken]）时，输入框上方提示可用的斜杠命令；点一条只换掉那个词。 */
@Composable
private fun ComposerSlash(
    state: TextFieldState,
    commands: List<SlashCommand>,
    configCommandsEnabled: Boolean,
    onSlashTyped: () -> Unit,
) {
    val typed = state.text.toString()
    val cursor = state.selection
    val slash = if (cursor.collapsed) SlashToken.at(typed, cursor.start) else null
    LaunchedEffect(slash != null) { if (slash != null) onSlashTyped() }
    if (slash == null) return
    val available =
        remember(commands, configCommandsEnabled, UiLanguage.current) {
            ClaudeConfigCommand.suggestions(commands, configCommandsEnabled)
        }
    val suggestions =
        remember(slash.prefix, available) { slashSuggestions(slash.prefix, available) }
    if (suggestions.isEmpty()) return
    SlashSuggestions(suggestions) { name ->
        val (text, caret) = slash.complete(typed, name)
        state.edit {
            replace(0, length, text)
            selection = TextRange(caret)
        }
    }
}

/** 要提示的斜杠命令：名字以 [slash] 开头的排前面，名字里含有它的跟在后面；内部命令（“__”开头）不列。 */
private fun slashSuggestions(slash: String, commands: List<SlashCommand>): List<SlashCommand> {
    val visible = commands.filter { !it.name.startsWith("__") }
    return visible.filter { it.name.lowercase().startsWith(slash) } +
        visible.filter {
            !it.name.lowercase().startsWith(slash) && it.name.lowercase().contains(slash)
        }
}

/** 输入框上方的斜杠命令提示，点一条把命令填进输入框（[onPick]）。 */
@Composable
private fun SlashSuggestions(suggestions: List<SlashCommand>, onPick: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 232.dp).padding(bottom = 4.dp),
    ) {
        items(suggestions, key = { it.name }) { command ->
            SlashSuggestionRow(command) { onPick(command.name) }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 6.dp),
        color = colors.dividerLine,
    )
    Spacer(Modifier.height(4.dp))
}

/** 一条斜杠命令提示：命令名和参数提示一行，说明在下一行。 */
@Composable
private fun SlashSuggestionRow(command: SlashCommand, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(ZetaCorner.M))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "/${command.name}",
                style = MiuixTheme.textStyles.button,
                color = colors.primary,
                maxLines = 1,
            )
            if (command.argumentHint.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = command.argumentHint,
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (command.description.isNotBlank()) {
            Text(
                text = command.description,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 输入框上方一排待发的附件：文件（复制中、复制失败的注明）和图片缩略图（图片文件只画一张缩略图，复制中时淡一些），各带移除键，横向滚动。 */
@Composable
private fun ComposerAttachments(
    attachments: List<Attachment>,
    files: List<FileUpload>,
    onRemoveAttachment: (Attachment) -> Unit,
    onRemoveFile: (FileUpload) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        files.forEach { file ->
            if (
                !file.failed &&
                    (file.inline != null || file.path?.let(AttachedPaths::isImage) == true)
            ) {
                UploadThumbnail(file) { onRemoveFile(file) }
                return@forEach
            }
            FileChip(
                name = uploadLabel(file),
                modifier = Modifier.widthIn(max = 220.dp),
                folder = AttachedPaths.isFolder(file.path.orEmpty()),
            ) {
                FlatIconButton(
                    icon = MiuixIcons.Close,
                    contentDescription = Strings.composerRemove(),
                    size = 22.dp,
                    iconSize = 14.dp,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                ) {
                    onRemoveFile(file)
                }
            }
        }
        attachments.forEach { attachment ->
            RemovableThumbnail(onRemove = { onRemoveAttachment(attachment) }) {
                CroppedImage(attachment.preview, Strings.composerImageAttachment())
            }
        }
    }
}

/** 待发文件的名字，复制中、复制失败时在前面注明。 */
private fun uploadLabel(file: FileUpload): String =
    when {
        file.failed -> Strings.composerUploadFailed(file.name)
        file.uploading -> Strings.composerUploading(file.name)
        else -> file.name
    }

/** 待发的图片文件：画内联那份的小图，没有（放回的、草稿里恢复的）从主机上取；还在复制时淡一些。 */
@Composable
private fun UploadThumbnail(file: FileUpload, onRemove: () -> Unit) {
    val preview = file.inline?.preview
    val path = file.path
    RemovableThumbnail(onRemove, Modifier.alpha(if (file.uploading) 0.5f else 1f)) {
        when {
            preview != null -> CroppedImage(preview, file.name)
            path != null -> ChatThumbnail(FileImage(AttachedPaths.path(path)), size = 64.dp)
        }
    }
}

/** 裁切填满缩略图块的一张小图。 */
@Composable
private fun CroppedImage(bitmap: ImageBitmap, description: String) =
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )

/** 待发图片的 64dp 缩略图 [content]，右上角是移除键。 */
@Composable
private fun RemovableThumbnail(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(modifier) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(ZetaCorner.M))) { content() }
        FlatIconButton(
            icon = MiuixIcons.Close,
            contentDescription = Strings.composerRemove(),
            size = 20.dp,
            iconSize = 13.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp),
            backgroundColor = colors.windowDimming,
            tint = colors.onPrimary,
            onClick = onRemove,
        )
    }
}

/** 输入框；空着时下面垫一行占位提示，说话时垫着识别出的字（输入框这时是空的，松手后整段发出去）。 */
@Composable
private fun ComposerField(
    state: TextFieldState,
    talk: SpeechState,
    busy: Boolean,
    enabled: Boolean,
    textStyle: TextStyle,
    focus: FocusRequester,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = 36.dp)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        when {
            // 说话时输入框是空的：识别出的字实时显示在这里，松手后整段发出去
            talk.active ->
                Text(
                    text = talk.text.ifEmpty { listeningText(talk.phase) },
                    style = textStyle,
                    color = if (talk.text.isEmpty()) colors.primary else colors.onBackground,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            state.text.isEmpty() ->
                Text(
                    text = if (busy) Strings.composerHintBusy() else Strings.composerHintIdle(),
                    style = textStyle,
                    color = colors.onSecondaryContainer,
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
}

private fun listeningText(phase: SpeechPhase): String =
    if (phase == SpeechPhase.Finishing) Strings.composerRecognizing()
    else Strings.composerListening()

/** 附件菜单：图片、文件、文件夹（有的话）、输入路径一组，常用指令单独一组。 */
private fun attachMenu(
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: (() -> Unit)?,
    onEnterPath: () -> Unit,
    onOpenPrompts: () -> Unit,
): List<MenuEntry> =
    listOf(
        MenuEntry(
            items =
                listOfNotNull(
                    MenuItem(Strings.composerMenuImages(), MiuixIcons.Image, onPickImages),
                    MenuItem(Strings.composerMenuFiles(), ZetaIcons.Paperclip, onPickFiles),
                    onPickFolder?.let {
                        MenuItem(Strings.composerMenuFolder(), MiuixIcons.Folder, it)
                    },
                    MenuItem(Strings.composerMenuEnterPath(), MiuixIcons.Edit, onEnterPath),
                ),
        ),
        MenuEntry(
            items =
                listOf(
                    MenuItem(Strings.composerMenuPrompts(), MiuixIcons.Favorites, onOpenPrompts),
                ),
        ),
    )

/** 说话时底栏的提示：松开发送、上滑取消，滑进取消区时标红。 */
@Composable
private fun TalkHint(talk: SpeechState, cancelling: Boolean, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    Text(
        text =
            when {
                talk.phase == SpeechPhase.Finishing -> Strings.composerTalkReleased()
                cancelling -> Strings.composerTalkReleaseCancel()
                else -> Strings.composerTalkReleaseSend()
            },
        style = MiuixTheme.textStyles.footnote1,
        color = if (cancelling) colors.error else colors.onBackgroundVariant,
        modifier = modifier,
        maxLines = 1,
    )
}

/** 底栏右端的圆形键：有东西可发时是发送，在跑又没东西可发时是停止；没东西可发、也没在跑时（或正说着话）换成按住说话的麦克风。 说话时随音量放大一圈光晕。 */
@Composable
private fun SendButton(
    state: TextFieldState,
    talk: SpeechState,
    cancelling: Boolean,
    busy: Boolean,
    canSend: Boolean,
    enabled: Boolean,
    voice: HoldToTalk?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val talking = talk.active
    val showStop = busy && !canSend
    // 没东西可发、也没在跑时（或正说着话），发送键换成按住说话的麦克风
    val mic = voice != null && enabled && (talking || (!showStop && !canSend))
    val buttonColor by
        animateColorAsState(
            targetValue = sendButtonTarget(cancelling, talking, showStop, canSend),
            animationSpec = tween(160),
            label = "send-color",
        )
    val haptics = LocalHapticFeedback.current
    val level by
        animateFloatAsState(
            if (talk.phase == SpeechPhase.Listening) talk.level else 0f,
            tween(90),
            label = "talk-level",
        )
    val grow by animateFloatAsState(if (talking) 1.12f else 1f, tween(160), label = "talk-grow")
    Box(
        modifier =
            Modifier.size(36.dp)
                .then(
                    if (mic && voice != null) {
                        Modifier.semantics { contentDescription = Strings.composerHoldToTalk() }
                            .holdToTalk(voice, haptics) { text -> onSend(text) }
                    } else {
                        Modifier.clip(CircleShape).clickable(enabled = showStop || canSend) {
                            sendOrStop(state, showStop, onSend, onStop)
                        }
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        // 说话时按钮后面一圈随音量放大的光晕
        if (talking) {
            Box(
                Modifier.size(36.dp)
                    .graphicsLayer {
                        val scale = 1f + 0.45f * level
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(buttonColor.copy(alpha = 0.22f), CircleShape),
            )
        }
        Box(
            modifier =
                Modifier.size(32.dp)
                    .graphicsLayer {
                        scaleX = grow
                        scaleY = grow
                    }
                    .clip(CircleShape)
                    .background(buttonColor),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = sendFace(mic, showStop),
                transitionSpec = {
                    (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f)).togetherWith(
                        fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.72f),
                    )
                },
                label = "send-stop",
            ) { shown ->
                SendFaceIcon(shown, talking, canSend)
            }
        }
    }
}

/** 发送键底色的目标颜色：说话时是主题色（滑进取消区是红色），停止键深色，能发时主题色，否则灰色。 */
@Composable
private fun sendButtonTarget(
    cancelling: Boolean,
    talking: Boolean,
    showStop: Boolean,
    canSend: Boolean,
): Color {
    val colors = MiuixTheme.colorScheme
    return when {
        cancelling -> colors.error
        talking -> colors.primary
        showStop -> colors.onBackground
        canSend -> colors.primary
        else -> colors.fill8
    }
}

private fun sendFace(mic: Boolean, showStop: Boolean): SendFace =
    when {
        mic -> SendFace.Mic
        showStop -> SendFace.Stop
        else -> SendFace.Send
    }

/** 点发送键：在跑又没东西可发时停下这一轮，否则发出输入框里的字并清空。 */
private fun sendOrStop(
    state: TextFieldState,
    showStop: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    if (showStop) {
        onStop()
    } else {
        val text = state.text.toString()
        state.clearText()
        onSend(text)
    }
}

/** 发送键上的图标：麦克风、停止或向上的箭头。 */
@Composable
private fun SendFaceIcon(shown: SendFace, talking: Boolean, canSend: Boolean) {
    val colors = MiuixTheme.colorScheme
    Icon(
        imageVector =
            when (shown) {
                SendFace.Mic -> MiuixIcons.Mic
                SendFace.Stop -> ZetaIcons.Stop
                SendFace.Send -> ZetaIcons.ArrowUp
            },
        contentDescription =
            when (shown) {
                SendFace.Mic -> null
                SendFace.Stop -> Strings.composerStop()
                SendFace.Send -> Strings.composerSend()
            },
        modifier =
            Modifier.size(
                when (shown) {
                    SendFace.Mic -> 17.dp
                    SendFace.Stop -> 12.dp
                    SendFace.Send -> 17.dp
                },
            ),
        tint =
            when {
                shown == SendFace.Mic && talking -> colors.onPrimary
                shown == SendFace.Mic -> colors.onBackgroundVariant
                shown == SendFace.Stop -> colors.background
                canSend -> colors.onPrimary
                else -> colors.onSurfaceVariantActions
            },
    )
}

/** 发送键上显示什么。 */
private enum class SendFace {
    Send,
    Stop,
    Mic,
}

/** 没有按住说话时的识别状态（一直空闲）。 */
private val IdleTalk: StateFlow<SpeechState> = MutableStateFlow(SpeechState())

/**
 * 权限模式按钮：一面盾牌，里面的字母和颜色看模式，颜色固定。 逐项确认和没指定时是灰色的 M，计划模式青色的 P，自动接受编辑紫色的 E，自动判断蓝色的 A，完全放行红色的感叹号。
 * 模式名在读屏说明、长按提示和点开的单选菜单里。
 */
@Composable
fun PermissionModeChip(mode: String?, onChange: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val (icon, tone) =
        when (mode) {
            "auto" -> ZetaIcons.ShieldA to colors.accentBlue
            "plan" -> ZetaIcons.ShieldP to colors.accentCyan
            "acceptEdits" -> ZetaIcons.ShieldE to colors.accentPurple
            "bypassPermissions" -> ZetaIcons.ShieldAlert to colors.error
            else -> ZetaIcons.ShieldM to null
        }
    val entries =
        listOf(
            MenuEntry(
                items =
                    PermissionModes.map { (value, label) ->
                        MenuItem(
                            text = label,
                            selected = value == mode,
                            onClick = { onChange(value) },
                        )
                    },
            ),
        )
    BarMenuButton(
        description = Strings.composerPermissionMode(permissionModeLabel(mode)),
        entries = entries,
        colors = barChipColors(tone),
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}
