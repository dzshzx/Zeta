package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.ComposerDraft
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileLimits
import com.dzshzx.zeta.core.FileUpload
import com.dzshzx.zeta.core.Intake
import com.dzshzx.zeta.core.IntakeParser
import com.dzshzx.zeta.core.LinuxShell
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.QueuedMessage
import com.dzshzx.zeta.core.Uploads
import com.dzshzx.zeta.core.dropIntake
import com.dzshzx.zeta.core.intakeTarget
import com.dzshzx.zeta.core.placeIntake
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.DialogButtonBar
import com.dzshzx.zeta.ui.components.miuix.DialogButtonBarAction
import com.dzshzx.zeta.ui.components.miuix.NoticeDialog
import com.dzshzx.zeta.ui.components.rememberRetained
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 输入栏这一头：把送进来的东西（[Intake]）、附件菜单里选的图片和文件、撤回的排队消息放进这个对话的草稿 [draft]。
 * 文字接在草稿后面；截屏做成图片附件，放不下的当文件；选的、分享来的文件原样复制进对话所在的主机，图片另带一张缩小的内联图。 太大的文件不收，没收的和原因攒在 [problems] 里，由
 * [IntakeProblemsDialog] 告诉用户。
 */
@Stable
class IntakeSink(
    private val hub: ConversationHub,
    private val scope: CoroutineScope,
    private val draft: ComposerDraft,
    private val maxImages: Int,
    /** 对话在哪台主机上（[Backends]）：文件复制到那台上。 */
    private val backend: String,
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
            if (draft.attachments.size < maxImages) {
                draft.attachments.add(image)
            } else {
                addBytes(screenshotName(), Base64.getDecoder().decode(image.base64))
            }
        }
        if (intake.uris.isNotEmpty()) addFiles(intake.uris.map(hub.platform::sharedFile))
    }

    /**
     * 撤回的排队消息（点了它，或按了停止）放回输入栏：文字接在已有草稿前面，附件和路径补上没有的；图片文件的缩小图配回文件上 （[ComposerDraft.putBackImages]）。
     */
    fun putBack(message: QueuedMessage) {
        val typed = draft.input.text.toString()
        draft.input.setTextAndPlaceCursorAtEnd(
            listOf(message.text, typed).filter { it.isNotBlank() }.joinToString("\n"),
        )
        message.files.forEach(draft.uploads::addPath)
        draft.attachments.addAll(
            draft
                .putBackImages(message.attachments)
                .filter { a -> draft.attachments.none { it.id == a.id } }
                .take(maxImages - draft.attachments.size),
        )
    }

    /** 文字接在草稿后面；太长的存成文本文件当附件，免得把输入框撑爆。 */
    private fun addText(text: String) {
        if (text.length <= IntakeParser.MAX_INLINE_TEXT) {
            draft.input.setTextAndPlaceCursorAtEnd(
                IntakeParser.mergeDraft(draft.input.text.toString(), text),
            )
        } else {
            addBytes(Strings.composerSharedTextFile(), text.toByteArray())
        }
    }

    /**
     * 选的、分享来的文件：原样复制进对话所在的主机，让 Claude Code 用工具去读。图片也复制原文件，另随消息内联一张缩小的给模型先看 （[FileUpload.inline]）。
     */
    fun addFiles(files: List<PickedFile>) {
        if (files.size > FileLimits.MAX_FILES) {
            report(
                Strings.composerTooManyFiles(
                    FileLimits.MAX_FILES,
                    files.size - FileLimits.MAX_FILES,
                ),
            )
        }
        scope.launch { for (file in files.take(FileLimits.MAX_FILES)) addFile(file) }
    }

    /** 收一个文件：检查大小后复制进主机（在这个协程里另起一个复制）；图片另在后台做一张缩小的内联图（[FileUpload.inline]）。 */
    private suspend fun CoroutineScope.addFile(file: PickedFile) {
        val (name, type) = withContext(Dispatchers.IO) { file.describe() }
        val size = withContext(Dispatchers.IO) { file.size() }
        if (FileLimits.tooLarge(size)) {
            report(FileLimits.tooLargeReason(name, size), tooLarge = true)
            return
        }
        val shell =
            hub.shell(backend)
                ?: run {
                    report(
                        Strings.composerNotCopied(
                            name,
                            hub.unavailable(backend) ?: Strings.composerDeviceUnavailable(),
                        ),
                    )
                    return
                }
        val upload = FileUpload(UUID.randomUUID().toString(), FileLimits.label(name, size))
        draft.uploads.add(upload)
        if (type?.startsWith("image/") == true || AttachedPaths.isImage(name)) {
            launch { upload.inline = withContext(Dispatchers.IO) { file.loadImage() } }
        }
        launch { copyUpload(shell, file, name, upload) }
    }

    /** 把文件复制进 Linux，结果记在 [upload] 上；复制时才发现太大的，从输入栏拿掉并说明。 */
    private suspend fun copyUpload(
        shell: LinuxShell,
        file: PickedFile,
        name: String,
        upload: FileUpload,
    ) {
        val copied =
            withContext(Dispatchers.IO) {
                Uploads.copyFrom(shell, name, FileLimits.MAX_BYTES, file::open)
            }
        when {
            copied.path != null -> upload.path = copied.path
            copied.tooLarge -> {
                draft.uploads.remove(upload)
                report(FileLimits.tooLargeReason(name, null), tooLarge = true)
            }
            else -> upload.failed = true
        }
    }

    /** 内存里的一段内容（太长的文字、放不下的截屏）当文件复制进 Linux。 */
    private fun addBytes(name: String, bytes: ByteArray) {
        val shell =
            hub.shell(backend)
                ?: run {
                    report(
                        Strings.composerNotCopied(
                            name,
                            hub.unavailable(backend) ?: Strings.composerDeviceUnavailable(),
                        ),
                    )
                    return
                }
        val upload =
            FileUpload(UUID.randomUUID().toString(), FileLimits.label(name, bytes.size.toLong()))
        draft.uploads.add(upload)
        scope.launch {
            val path = withContext(Dispatchers.IO) { Uploads.copyBytes(shell, name, bytes) }
            if (path != null) upload.path = path else upload.failed = true
        }
    }

    private fun screenshotName(): String =
        Strings.composerScreenshotFile(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
        )
}

/** 告诉用户哪些没有加进来、为什么。放在聊天页（Miuix 的 Scaffold）里。 */
@Composable
fun IntakeProblemsDialog(sink: IntakeSink) {
    val problems = sink.problems
    // 关闭的动画里内容已经清空：留着上一次的说明
    val message =
        rememberRetained(
            problems
                .takeIf { it.isNotEmpty() }
                ?.let { list ->
                    list.joinToString("\n") { Strings.composerProblemLine(it) } +
                        if (sink.rejectedLarge) "\n\n" + Strings.composerTooLargeAdvice() else ""
                },
        )
    NoticeDialog(
        show = problems.isNotEmpty(),
        title = Strings.composerIntakeProblemsTitle(),
        summary = message,
        onDismissRequest = sink::dismissProblems,
    )
}

/**
 * 「放进哪个对话」：外面送来的东西（分享、打开、截屏）放进新对话，还是接着最近那个对话。 只有当前对话有内容、或者后台还有对话在进行时才会问（见
 * [com.dzshzx.zeta.core.offerIntake]）。点外面或返回就不要了。
 *
 * 它在应用最外层、盖在所有页面上面，不在任何 Miuix 的 Scaffold 里，所以用窗口版的对话框（没有页面毛玻璃）。
 */
@Composable
fun IntakeChooser(hub: ConversationHub) {
    val pending = hub.pendingIntake
    val message =
        rememberRetained(
            pending?.let {
                val summary = it.summary
                val title = hub.intakeTarget()?.title?.trim()?.ifBlank { null }
                val lead = if (summary.first().isDigit()) "digit" else "other"
                if (title != null) Strings.composerIntakeAskTitled(lead, summary, title)
                else Strings.composerIntakeAsk(lead, summary)
            },
        )
    WindowDialog(
        show = pending != null,
        title = Strings.composerIntakeTitle(),
        summary = message,
        onDismissRequest = hub::dropIntake,
    ) {
        DialogButtonBar(
            negative =
                DialogButtonBarAction(
                    Strings.composerNewConversation(),
                    onClick = { hub.placeIntake(intoNew = true) },
                ),
            positive =
                DialogButtonBarAction(
                    Strings.composerContinueThis(),
                    recommended = true,
                    onClick = { hub.placeIntake(intoNew = false) },
                ),
        )
    }
}
