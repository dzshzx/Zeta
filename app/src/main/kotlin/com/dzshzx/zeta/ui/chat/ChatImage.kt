package com.dzshzx.zeta.ui.chat

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.ImageStore
import com.dzshzx.zeta.core.InlineImage
import com.dzshzx.zeta.core.RemoteImage
import com.dzshzx.zeta.core.get
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 图片的载入结果：bitmap 为 null 表示取不到。 */
internal class Loaded(val bitmap: ImageBitmap?)

/** 聊天里图片的圆角。 */
internal val ChatImageShape = RoundedCornerShape(14.dp)

/** 聊天页正显示的对话在哪台主机上（[Backends]）：会话记录、文件里的图片从那台取。由聊天页提供。 */
internal val LocalBackend = staticCompositionLocalOf { Backends.LOCAL }

/** 取一张图：null 表示还在取。 */
@Composable
internal fun rememberChatImage(ref: ImageRef): Loaded? {
    val hub = ConversationHub.get(LocalContext.current.applicationContext as Application)
    val backend = LocalBackend.current
    return produceState(
            initialValue = ImageStore.cached(ref, backend)?.let(::Loaded),
            ref.key,
            backend,
        ) {
            if (value == null) value = Loaded(ImageStore.load(hub, ref, backend))
        }
        .value
}

/** 网络图片要不要等用户点一下：访问图片地址会把你的 IP 暴露给对方服务器，默认先问。 */
@Composable
internal fun waitsForTap(ref: ImageRef): Boolean {
    if (ref !is RemoteImage) return false
    val hub = ConversationHub.get(LocalContext.current.applicationContext as Application)
    return !hub.settings.autoLoadRemoteImages &&
        !ImageStore.isAllowed(ref) &&
        ImageStore.cached(ref, LocalBackend.current) == null
}

@Composable
internal fun RemoteImagePrompt(ref: RemoteImage, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.05f))
                .clickable { ImageStore.allow(ref) }
                .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            COUIIcons.Image,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = COUITheme.colorScheme.primary,
        )
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
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = loaded?.bitmap
        when {
            bitmap != null ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable { viewing = true },
                )
            loaded != null ->
                Icon(
                    ZetaIcons.ImageOff,
                    contentDescription = "图片无法显示",
                    modifier = Modifier.size(22.dp),
                    tint = COUITheme.colorScheme.labelSecondary,
                )
        }
    }
    if (viewing) ImageViewer(listOf(ref), 0) { viewing = false }
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
    val bitmap = loaded?.bitmap
    when {
        bitmap != null -> {
            val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            Image(
                bitmap = bitmap,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier =
                    modifier
                        .then(
                            if (ratio >= 0.8f) Modifier.fillMaxWidth().aspectRatio(ratio)
                            else Modifier.height(340.dp).aspectRatio(ratio),
                        )
                        .clip(ChatImageShape)
                        .clickable { viewing = true },
            )
        }
        loaded == null -> ImageLoading(modifier.fillMaxWidth().height(120.dp))
        else -> ImageUnavailable(ref, modifier)
    }
    if (viewing) ImageViewer(listOf(ref), 0) { viewing = false }
}

/** 还在取的图片：浅灰的占位块，大小由 [modifier] 定。 */
@Composable
internal fun ImageLoading(modifier: Modifier) {
    Box(
        modifier
            .clip(ChatImageShape)
            .background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.05f)),
    )
}

/** 取不到的图片：一行「图片无法显示」，文件里的图带上文件名。 */
@Composable
internal fun ImageUnavailable(ref: ImageRef, modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(COUITheme.colorScheme.labelPrimary.copy(alpha = 0.05f))
                .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            ZetaIcons.ImageOff,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = COUITheme.colorScheme.labelSecondary,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text =
                "图片无法显示" +
                    ((ref as? FileImage)?.let { "：${it.path.substringAfterLast('/')}" } ?: ""),
            style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp),
            color = COUITheme.colorScheme.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 附件文件的小标签：图标加文件名。 */
@Composable
internal fun FileChip(
    name: String,
    modifier: Modifier = Modifier,
    folder: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = COUITheme.colorScheme.isDark
    val colors = COUITheme.colorScheme
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.labelPrimary.copy(alpha = if (dark) 0.08f else 0.05f))
                .padding(
                    start = 10.dp,
                    end = if (trailing == null) 12.dp else 6.dp,
                    top = 7.dp,
                    bottom = 7.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (folder) COUIIcons.Folder else COUIIcons.File,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = colors.primary,
        )
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

/**
 * 一段回答拆成两部分：照常排版的正文，和从正文里取出来直接显示的图片。图片按原文里的先后分成 [groups]： 只隔着空白的几张连成一组（显示成一个能左右翻的图片组），被正文隔开的各成一组。
 */
internal class AnswerImages(val text: String, val groups: List<List<ImageRef>>)

/** 一段回答里最多直接显示几张图，多的原样留在正文里。 */
private const val MaxAnswerImages = 12

/**
 * 找出回答里用 Markdown 引用的图片（`![说明](/路径)`、网络地址、内嵌的 data:），并把这些写法从正文里拿掉——图片本身会显示在正文下面，
 * 不必再留一行替代文字。相对路径按工作目录解析；代码块里的不算；同一张图只显示一次。
 */
internal fun answerImages(text: String, cwd: String, home: String?): AnswerImages {
    if (!text.contains("![")) return AnswerImages(text, emptyList())
    val fences = CodeFence.findAll(text).map { it.range }.toList()
    val picker = ImagePicker(text)
    val shown =
        MarkdownImage.replace(text) { match ->
            val ref =
                if (fences.any { match.range.first in it }) null
                else localImage(match.groupValues[1], cwd, home)
            if (ref != null && picker.take(ref, match.range)) "" else match.value
        }
    return AnswerImages(if (picker.groups.isEmpty()) text else shown.trimEnd(), picker.groups)
}

/** 按原文里的先后收下回答里的图片，顺带分组：和上一张之间只有空白的接进上一组。 */
private class ImagePicker(private val text: String) {
    val groups = ArrayList<MutableList<ImageRef>>()
    private val seen = HashSet<String>()
    private var lastEnd = -1
    private var open = false

    /** 收下原文 [range] 处的 [ref] 就返回 true，这处写法要从正文里拿掉；收满了的新图返回 false，留在正文里。 */
    fun take(ref: ImageRef, range: IntRange): Boolean {
        val fresh = ref.key !in seen
        if (fresh && seen.size >= MaxAnswerImages) return false
        // 重复的图不再显示，也不打断连着的一组；它前面隔着正文时，后面的图另起一组
        if (lastEnd < 0 || text.substring(lastEnd, range.first).isNotBlank()) open = false
        if (fresh) {
            if (open) groups.last().add(ref) else groups.add(mutableListOf(ref))
            seen.add(ref.key)
            open = true
        }
        lastEnd = range.last + 1
        return true
    }
}

private fun localImage(target: String, cwd: String, home: String?): ImageRef? =
    when {
        target.startsWith("data:image/") && target.contains(";base64,") ->
            InlineImage(
                "d:${target.hashCode()}:${target.length}",
                target.substringAfter(";base64,"),
            )
        target.startsWith("https://") || target.startsWith("http://") -> RemoteImage(target)
        target.contains("://") && !target.startsWith("file://") -> null
        else -> {
            val raw = target.removePrefix("file://").replace("%20", " ")
            val path =
                when {
                    raw.startsWith("/") -> raw
                    raw.startsWith("~/") && home != null -> home + raw.substring(1)
                    else -> cwd.trimEnd('/') + "/" + raw.removePrefix("./")
                }
            if (path.substringAfterLast('.', "").lowercase() in ImageSuffixes) FileImage(path)
            else null
        }
    }
