package com.dzshzx.zeta.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileLimits
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.Intake
import com.dzshzx.zeta.core.IntakeParser
import com.dzshzx.zeta.core.Uploads
import com.dzshzx.zeta.core.dropIntake
import com.dzshzx.zeta.core.intakeTarget
import com.dzshzx.zeta.core.placeIntake
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.window.WindowDialog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入栏这一头：把送进来的东西（[Intake]）和附件菜单里选的图片、文件放进输入栏。
 * 文字接在草稿后面；图片做成图片附件，放不下的、读不了的当文件；别的文件复制进 Linux。
 * 太大的文件不收，没收的和原因攒在 [problems] 里，由 [IntakeProblemsDialog] 告诉用户。
 */
@Stable
internal class IntakeSink(
    private val hub: ConversationHub,
    private val context: Context,
    private val scope: CoroutineScope,
    private val input: TextFieldState,
    private val attachments: SnapshotStateList<Attachment>,
    private val uploads: SnapshotStateList<FileUpload>,
    private val maxImages: Int,
) {
    /** 没有加进来的东西和原因，一条一句。 */
    var problems by mutableStateOf<List<String>>(emptyList())
        private set

    /** [problems] 里有因为太大没收的：说明里要讲为什么、该怎么办。 */
    var rejectedLarge by mutableStateOf(false)
        private set

    fun dismissProblems() {
        problems = emptyList()
        rejectedLarge = false
    }

    private fun report(reason: String, tooLarge: Boolean = false) {
        problems = problems + reason
        if (tooLarge) rejectedLarge = true
    }

    /** 放进来一份 [Intake]（已经定了是这个对话）。在主线程上调用。 */
    fun accept(intake: Intake) {
        intake.text?.takeIf { it.isNotBlank() }?.let(::addText)
        for (image in intake.images) {
            if (attachments.size < maxImages) {
                attachments.add(image)
            } else {
                addBytes(screenshotName(), Base64.decode(image.base64, Base64.NO_WRAP))
            }
        }
        if (intake.uris.isNotEmpty()) addFiles(intake.uris.map(Uri::parse))
    }

    /** 文字接在草稿后面；太长的存成文本文件当附件，免得把输入框撑爆。 */
    private fun addText(text: String) {
        if (text.length <= IntakeParser.MAX_INLINE_TEXT) {
            input.setTextAndPlaceCursorAtEnd(IntakeParser.mergeDraft(input.text.toString(), text))
        } else {
            addBytes("分享的文字.txt", text.toByteArray())
        }
    }

    /** 附件菜单的「图片」：选的都当图片附件。 */
    fun addImages(uris: List<Uri>) {
        scope.launch {
            for (uri in uris.take(maxImages - attachments.size)) {
                val loaded = withContext(Dispatchers.IO) { Attachments.load(context, uri) }
                if (loaded != null) attachments.add(loaded) else report("有一张图片读不了，没有加进来")
            }
        }
    }

    /** 文件：图片在输入栏还放得下时当图片附件发给模型看，别的复制进 Linux 让 Claude 用工具去读。 */
    fun addFiles(uris: List<Uri>) {
        if (uris.size > FileLimits.MAX_FILES) {
            report("一次最多收 ${FileLimits.MAX_FILES} 个文件，另外 ${uris.size - FileLimits.MAX_FILES} 个没有加进来")
        }
        scope.launch {
            for (uri in uris.take(FileLimits.MAX_FILES)) {
                val (name, type) = withContext(Dispatchers.IO) { Uploads.describe(context, uri) }
                if (type?.startsWith("image/") == true && type != "image/svg+xml" && attachments.size < maxImages) {
                    val loaded = withContext(Dispatchers.IO) { Attachments.load(context, uri) }
                    if (loaded != null) {
                        attachments.add(loaded)
                        continue
                    }
                }
                val size = withContext(Dispatchers.IO) { Uploads.size(context, uri) }
                if (FileLimits.tooLarge(size)) {
                    report(FileLimits.tooLargeReason(name, size), tooLarge = true)
                    continue
                }
                val config = hub.settings.env ?: run {
                    report("「$name」没有复制：还没有 Linux 环境")
                    continue
                }
                val upload = FileUpload(UUID.randomUUID().toString(), FileLimits.label(name, size))
                uploads.add(upload)
                launch {
                    val copied = withContext(Dispatchers.IO) { Uploads.copy(context, config, uri, name, FileLimits.MAX_BYTES) }
                    when {
                        copied.path != null -> upload.path = copied.path
                        copied.tooLarge -> {
                            uploads.remove(upload)
                            report(FileLimits.tooLargeReason(name, null), tooLarge = true)
                        }
                        else -> upload.failed = true
                    }
                }
            }
        }
    }

    /** 内存里的一段内容（太长的文字、放不下的截屏）当文件复制进 Linux。 */
    private fun addBytes(name: String, bytes: ByteArray) {
        val config = hub.settings.env ?: run {
            report("「$name」没有复制：还没有 Linux 环境")
            return
        }
        val upload = FileUpload(UUID.randomUUID().toString(), FileLimits.label(name, bytes.size.toLong()))
        uploads.add(upload)
        scope.launch {
            val path = withContext(Dispatchers.IO) { Uploads.copyBytes(config, name, bytes) }
            if (path != null) upload.path = path else upload.failed = true
        }
    }

    private fun screenshotName(): String = "截屏-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jpg"
}

/** 告诉用户哪些没有加进来、为什么。放在聊天页（COUI 的 Scaffold）里。 */
@Composable
internal fun IntakeProblemsDialog(sink: IntakeSink) {
    val problems = sink.problems
    // 关闭的动画里内容已经清空：留着上一次的说明
    val message = rememberRetained(
        problems.takeIf { it.isNotEmpty() }?.let { list ->
            list.joinToString("\n") { "$it。" } + if (sink.rejectedLarge) "\n\n$TooLargeAdvice" else ""
        },
    )
    NoticeDialog(
        show = problems.isNotEmpty(),
        title = "有些内容没有加进来",
        summary = message,
        onDismissRequest = sink::dismissProblems,
    )
}

private const val TooLargeAdvice = "太大的文件复制进 Linux 要多占一份同样大的空间，Claude 也读不完。" +
    "要让 Claude 处理它，可以在 设置 › Linux 环境 › 共享文件夹 里加上它所在的文件夹，再从附件菜单的「输入路径」引用。"

/**
 * 「放进哪个对话」：外面送来的东西（分享、打开、截屏）放进新对话，还是接着最近那个对话。
 * 只有当前对话有内容、或者后台还有对话在进行时才会问（见 [com.dzshzx.zeta.core.offerIntake]）。点外面或返回就不要了。
 *
 * 它在应用最外层、盖在所有页面上面，不在任何 COUI 的 Scaffold 里，所以用窗口版的对话框（没有页面毛玻璃）。
 */
@Composable
internal fun IntakeChooser(hub: ConversationHub) {
    val pending = hub.pendingIntake
    val message = rememberRetained(
        pending?.let {
            val summary = it.summary
            val title = hub.intakeTarget()?.title?.trim()?.ifBlank { null }
            "收到" + (if (summary.first().isDigit()) " " else "") + summary + "。" +
                (if (title != null) "接着「$title」，还是开一个新对话？" else "接着刚才的对话，还是开一个新对话？")
        },
    )
    WindowDialog(show = pending != null, title = "放进哪个对话", summary = message, onDismissRequest = hub::dropIntake) {
        DialogButtonBar(
            negative = DialogButtonBarAction("新对话", onClick = { hub.placeIntake(intoNew = true) }),
            positive = DialogButtonBarAction("接着这个对话", recommended = true, onClick = { hub.placeIntake(intoNew = false) }),
        )
    }
}
