package com.dzshzx.zeta.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ImageExport
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.ui.chat.ChatPlatform
import com.dzshzx.zeta.ui.chat.FilePickers
import com.dzshzx.zeta.ui.chat.ImageExporter
import com.dzshzx.zeta.ui.chat.RemoteChoice
import com.dzshzx.zeta.ui.chat.SetupMessage
import com.dzshzx.zeta.ui.chat.TalkInput
import com.dzshzx.zeta.ui.components.LocalHub
import com.dzshzx.zeta.ui.pane.SessionExporter
import com.dzshzx.zeta.ui.pane.combineExports
import com.dzshzx.zeta.ui.pane.exportMarkdown
import com.dzshzx.zeta.ui.pane.exportMarkdowns
import com.dzshzx.zeta.ui.pane.exportName
import com.dzshzx.zeta.ui.pane.exportedMessage
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 桌面上聊天页的平台部分：没有手机工具、朗读和语音；选文件、存导出用系统的文件对话框，分享是复制到剪贴板。登记在 desktop 的 META-INF/services 里。 */
class DesktopChatPlatform : ChatPlatform {
    override fun phoneToolTitle(tool: String, input: JsonObject): String? = null

    @Composable override fun ToolStepAction(step: ToolStep) = Unit

    @Composable override fun ReadAloudButton(answer: AnswerItem) = Unit

    @Composable
    override fun rememberFilePickers(
        maxImages: Int,
        onImages: (List<PickedFile>) -> Unit,
        onFiles: (List<PickedFile>) -> Unit,
    ): FilePickers =
        remember(maxImages, onImages, onFiles) {
            object : FilePickers {
                override fun pickImages() {
                    val chosen = openFiles("选择图片", IMAGE_EXTENSIONS)
                    if (chosen.isNotEmpty()) onImages(chosen.take(maxImages).map(::DesktopFile))
                }

                override fun pickFiles() {
                    val chosen = openFiles("选择文件", null)
                    if (chosen.isNotEmpty()) onFiles(chosen.map(::DesktopFile))
                }
            }
        }

    @Composable
    override fun rememberSessionExporter(
        hub: ConversationHub,
        onSavedMany: () -> Unit,
    ): SessionExporter {
        val scope = rememberCoroutineScope()
        @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
        fun save(sessions: List<SessionSummary>, batch: Boolean) {
            if (sessions.isEmpty()) return
            val target = saveFile("导出对话", exportName(sessions)) ?: return
            if (batch) onSavedMany()
            scope.launch {
                val saved = withContext(Dispatchers.IO) { writeExports(hub, sessions, target) }
                hub.platform.toast(exportedMessage(saved, sessions.size))
            }
        }
        return SessionExporter(
            save = { save(listOf(it), batch = false) },
            share = { session ->
                scope.launch {
                    val exported =
                        withContext(Dispatchers.IO) {
                            exportMarkdown(hub, session.backend, session.file)
                        }
                    val copied = exported?.also { clipboard.setText(AnnotatedString(it.markdown)) }
                    hub.platform.toast(if (copied == null) "导出失败" else "已把对话复制到剪贴板")
                }
            },
            saveAll = { save(it, batch = true) },
        )
    }

    /** 桌面上不会引用手机上的文件夹（只有手机本机那台能引用）。 */
    @Composable
    override fun rememberFolderTreePicker(onPicked: (String?) -> Unit): (String) -> Unit = {}

    @Composable
    override fun Unavailable(
        hub: ConversationHub,
        padding: PaddingValues,
        onOpenInstall: () -> Unit,
        onOpenRemoteHosts: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val (title, text) =
            if (hub.settings.remoteHosts.isEmpty()) {
                "还没有远程设备" to
                    "Zeta 桌面版用远程设备上的 Claude Code。先添加一台：填它的地址，再把 Zeta 的公钥加进它的 ~/.ssh/authorized_keys。"
            } else {
                "这台设备用不了" to hub.unavailable(hub.current.backend)
            }
        SetupMessage(padding, title, text) { RemoteChoice(hub, onOpenRemoteHosts) }
    }

    @Composable
    override fun ConversationStrip(
        hub: ConversationHub,
        conversation: Conversation,
        modifier: Modifier,
    ) = Unit

    @Composable override fun ImmersiveImageWindow() = Unit

    /** 保存用系统的保存文件对话框；分享是把图复制到剪贴板（桌面上没有系统分享面板）。 */
    @Composable
    override fun rememberImageExporter(): ImageExporter {
        val hub = LocalHub.current
        val scope = rememberCoroutineScope()
        // 写文件、解码大图放到 IO 线程，提示回界面线程再弹
        fun inBackground(done: String, failed: String, work: () -> Unit) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching(work).isSuccess }
                hub.platform.toast(if (ok) done else failed)
            }
        }
        return remember(hub, scope) {
            object : ImageExporter {
                override fun save(image: ImageExport) {
                    val target = saveFile("保存图片", image.fileName) ?: return
                    inBackground("已保存", "保存失败") { target.writeBytes(image.bytes) }
                }

                override fun share(image: ImageExport) =
                    inBackground("已把图片复制到剪贴板", "复制失败") { ImageClipboard.copy(image.bytes) }
            }
        }
    }

    @Composable override fun rememberTalkInput(): TalkInput? = null

    private companion object {
        // 选图时只列 Skia 解得开的（聊天里认图片链接的 ImageSuffixes 还多 heic、avif，那两种桌面上解不开）
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        /** 把 [sessions] 导出成 Markdown 写进 [target]，返回导出成的会话数；一个都没成（或没写进文件）是 0。耗时，别在主线程调用。 */
        fun writeExports(hub: ConversationHub, sessions: List<SessionSummary>, target: File): Int {
            val exported =
                exportMarkdowns(hub, sessions[0].backend, sessions.map { it.file }).orEmpty()
            if (exported.isEmpty()) return 0
            val text = combineExports(exported)
            return if (runCatching { target.writeText(text) }.isSuccess) exported.size else 0
        }

        /** 系统的打开文件对话框，可多选；[extensions] 不为 null 时只列这些扩展名的。取消了是空的。 */
        fun openFiles(title: String, extensions: Set<String>?): List<File> {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isMultipleMode = true
            if (extensions != null) {
                dialog.setFilenameFilter { _, name ->
                    name.substringAfterLast('.', "").lowercase() in extensions
                }
            }
            dialog.isVisible = true
            return dialog.files.orEmpty().toList()
        }

        /** 系统的保存文件对话框，默认文件名 [name]；取消了是 null。 */
        fun saveFile(title: String, name: String): File? {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            dialog.file = name
            dialog.isVisible = true
            val chosen = dialog.file ?: return null
            return File(dialog.directory, chosen)
        }
    }
}
