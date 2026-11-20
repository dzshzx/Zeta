package com.dzshzx.zeta.ui.chat

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.ImageStore
import com.dzshzx.zeta.core.InlineImage
import com.dzshzx.zeta.core.RemoteImage
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 图片的载入结果：null 表示还在取。 */
private class Loaded(val bitmap: ImageBitmap?)

/** 聊天页正显示的对话在哪台主机上（[Backends]）：会话记录、文件里的图片从那台取。由聊天页提供。 */
internal val LocalBackend = staticCompositionLocalOf { Backends.LOCAL }

@Composable
private fun rememberChatImage(ref: ImageRef): Loaded? {
    val hub = ConversationHub.get(LocalContext.current.applicationContext as Application)
    val backend = LocalBackend.current
    return produceState(initialValue = ImageStore.cached(ref, backend)?.let(::Loaded), ref.key, backend) {
        if (value == null) value = Loaded(ImageStore.load(hub, ref, backend))
    }.value
}

/** 网络图片要不要等用户点一下：访问图片地址会把你的 IP 暴露给对方服务器，默认先问。 */
@Composable
private fun waitsForTap(ref: ImageRef): Boolean {
    if (ref !is RemoteImage) return false
    val hub = ConversationHub.get(LocalContext.current.applicationContext as Application)
    return !hub.settings.autoLoadRemoteImages && !ImageStore.isAllowed(ref) && ImageStore.cached(ref, LocalBackend.current) == null
}

@Composable
private fun RemoteImagePrompt(ref: RemoteImage, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.05f))
            .clickable { ImageStore.allow(ref) }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(COUIIcons.Image, contentDescription = null, modifier = Modifier.size(15.dp), tint = COUITheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "加载图片 · " + ref.url.substringAfter("://").substringBefore('/'),
            style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp),
            color = COUITheme.colorScheme.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 固定大小、裁切填满的小图（用户气泡、输入栏里的附件）。点一下看大图。 */
@Composable
internal fun ChatThumbnail(ref: ImageRef, size: Dp, modifier: Modifier = Modifier) {
    val loaded = rememberChatImage(ref)
    var viewing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier.size(size).clip(shape).background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = loaded?.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable { viewing = true },
            )
            loaded != null -> Icon(ZetaIcons.ImageOff, contentDescription = "图片无法显示", modifier = Modifier.size(22.dp), tint = COUITheme.colorScheme.labelSecondary)
        }
    }
    if (viewing) loaded?.bitmap?.let { ImageViewer(it) { viewing = false } }
}

/** 按原比例铺开的图片（回答里、工具结果里）。很高的图限制高度，免得一张图占满几屏。 */
@Composable
internal fun ChatImage(ref: ImageRef, modifier: Modifier = Modifier) {
    if (ref is RemoteImage && waitsForTap(ref)) {
        RemoteImagePrompt(ref, modifier)
        return
    }
    val loaded = rememberChatImage(ref)
    var viewing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val bitmap = loaded?.bitmap
    when {
        bitmap != null -> {
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            Image(
                bitmap = bitmap,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .then(if (ratio >= 0.8f) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.height(340.dp).aspectRatio(ratio))
                    .clip(shape)
                    .clickable { viewing = true },
            )
        }
        loaded == null -> Box(modifier.fillMaxWidth().height(120.dp).clip(shape).background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.05f)))
        else -> Row(
            modifier = modifier.clip(CircleShape).background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.05f)).padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZetaIcons.ImageOff, contentDescription = null, modifier = Modifier.size(14.dp), tint = COUITheme.colorScheme.labelSecondary)
            Spacer(Modifier.width(5.dp))
            Text(
                text = "图片无法显示" + ((ref as? FileImage)?.let { "：${it.path.substringAfterLast('/')}" } ?: ""),
                style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp),
                color = COUITheme.colorScheme.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (viewing && bitmap != null) ImageViewer(bitmap) { viewing = false }
}

/** 全屏看图：双指缩放、拖动，点一下关闭。 */
@Composable
private fun ImageViewer(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
                .transformable(transform),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
    }
}

/** 附件文件的小标签：图标加文件名。 */
@Composable
internal fun FileChip(name: String, modifier: Modifier = Modifier, folder: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val dark = COUITheme.colorScheme.isDark
    val colors = COUITheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.labelPrimary.copy(alpha = if (dark) 0.08f else 0.05f))
            .padding(start = 10.dp, end = if (trailing == null) 12.dp else 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (folder) COUIIcons.Folder else COUIIcons.File, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.primary)
        Text(
            text = name,
            style = COUITheme.textStyles.bodyS.copy(fontSize = 13.sp),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

private val MarkdownImage = Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+["'][^"']*["'])?\s*\)""")
private val CodeFence = Regex("""```.*?(```|$)""", RegexOption.DOT_MATCHES_ALL)
private val ImageSuffixes = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif")

/** 一段回答拆成两部分：照常排版的正文，和从正文里取出来直接显示的图片。 */
internal class AnswerImages(val text: String, val images: List<ImageRef>)

/**
 * 找出回答里用 Markdown 引用的本机图片（`![说明](/路径)`），并把这些写法从正文里拿掉——图片本身会显示在正文下面，
 * 不必再留一行替代文字。远程地址不加载，原样留在正文里当链接；相对路径按工作目录解析；代码块里的不算。
 */
internal fun answerImages(text: String, cwd: String, home: String?): AnswerImages {
    if (!text.contains("![")) return AnswerImages(text, emptyList())
    val fences = CodeFence.findAll(text).map { it.range }.toList()
    val images = LinkedHashMap<String, ImageRef>()
    val shown = MarkdownImage.replace(text) { match ->
        val ref = if (fences.any { match.range.first in it }) null else localImage(match.groupValues[1], cwd, home)
        if (ref == null || (ref.key !in images && images.size >= 12)) {
            match.value
        } else {
            images.getOrPut(ref.key) { ref }
            ""
        }
    }
    return AnswerImages(if (images.isEmpty()) text else shown.trimEnd(), images.values.toList())
}

private fun localImage(target: String, cwd: String, home: String?): ImageRef? = when {
    target.startsWith("data:image/") && target.contains(";base64,") ->
        InlineImage("d:${target.hashCode()}:${target.length}", target.substringAfter(";base64,"))
    target.startsWith("https://") || target.startsWith("http://") -> RemoteImage(target)
    target.contains("://") && !target.startsWith("file://") -> null
    else -> {
        val raw = target.removePrefix("file://").replace("%20", " ")
        val path = when {
            raw.startsWith("/") -> raw
            raw.startsWith("~/") && home != null -> home + raw.substring(1)
            else -> cwd.trimEnd('/') + "/" + raw.removePrefix("./")
        }
        if (path.substringAfterLast('.', "").lowercase() in ImageSuffixes) FileImage(path) else null
    }
}
