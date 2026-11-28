package com.dzshzx.zeta.ui.chat

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileLimits
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UriFile
import com.dzshzx.zeta.core.fromTreeUri
import com.dzshzx.zeta.core.voice.ReadAloud
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.PhoneToolCatalog
import com.dzshzx.zeta.phone.tools.BrowserTools
import com.dzshzx.zeta.ui.browser.BrowserStepButton
import com.dzshzx.zeta.ui.components.miuix.FlatIconButton
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import com.dzshzx.zeta.ui.pane.SessionExporter
import com.dzshzx.zeta.ui.pane.rememberAndroidSessionExporter
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 手机上聊天页的平台部分：手机工具的标题、浏览器那步的钮、按住说话。登记在 app 的 META-INF/services 里。 */
class AndroidChatPlatform : ChatPlatform {
    override fun phoneToolTitle(tool: String, input: JsonObject): String? =
        PhoneToolCatalog.title(tool, input)

    @Composable
    override fun ToolStepAction(step: ToolStep) {
        if (phoneToolSuffix(step.name) == BrowserTools.NAME) BrowserStepButton()
    }

    @Composable override fun ReadAloudButton(answer: AnswerItem) = PhoneReadAloudButton(answer)

    @Composable
    override fun rememberFilePickers(onPicked: (List<PickedFile>) -> Unit): FilePickers {
        val context = LocalContext.current.applicationContext
        val images =
            rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(FileLimits.MAX_FILES),
            ) { uris ->
                onPicked(uris.map { UriFile(context, it) })
            }
        val files =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                uris ->
                onPicked(uris.map { UriFile(context, it) })
            }
        return remember(images, files) {
            object : FilePickers {
                override fun pickImages() =
                    images.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )

                override fun pickFiles() = files.launch(arrayOf("*/*"))
            }
        }
    }

    @Composable
    override fun rememberSessionExporter(
        hub: ConversationHub,
        onSavedMany: () -> Unit,
    ): SessionExporter = rememberAndroidSessionExporter(hub, onSavedMany)

    @Composable
    override fun rememberFolderTreePicker(onPicked: (String?) -> Unit): (String) -> Unit {
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) onPicked(SharedFolders.fromTreeUri(uri))
            }
        return { path -> runCatching { launcher.launch(treeStart(path)) } }
    }

    @Composable
    override fun Unavailable(
        hub: ConversationHub,
        padding: PaddingValues,
        onOpenInstall: () -> Unit,
        onOpenRemoteHosts: () -> Unit,
        onOpenSettings: () -> Unit,
    ) = PhoneUnavailable(hub, padding, onOpenInstall, onOpenRemoteHosts, onOpenSettings)

    @Composable
    override fun ConversationStrip(
        hub: ConversationHub,
        conversation: Conversation,
        modifier: Modifier,
    ) = AgentDisplayRow(hub, conversation, modifier)

    @Composable override fun ImmersiveImageWindow() = ImmersiveWindow()

    @Composable override fun rememberImageExporter(): ImageExporter = rememberPhoneImageExporter()

    @Composable override fun rememberTalkInput(): TalkInput? = rememberSpeechTalk()
}

/**
 * 回答下方复制键后面的朗读键：点了用系统的朗读引擎念这段回答（Markdown 换成念得顺的文字，见 core/voice），
 * 正在念时换成主题色的停止键，再点停下。退到后台、锁屏后接着念，通知上也能停。
 */
@Composable
private fun PhoneReadAloudButton(answer: AnswerItem) {
    val context = LocalContext.current
    val reading by ReadAloud.state.collectAsState()
    val active = reading.id == answer.id
    FlatIconButton(
        icon = if (active) ZetaIcons.Stop else MiuixIcons.VolumeUp,
        contentDescription = if (active) Strings.chatStopReadAloud() else Strings.chatReadAloud(),
        size = 30.dp,
        iconSize = if (active) 11.dp else 15.dp,
        backgroundColor =
            if (active) MiuixTheme.colorScheme.tertiaryContainer else Color.Unspecified,
        tint =
            if (active) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onBackgroundVariant,
    ) {
        ReadAloud.toggle(context, answer.id, answer.text)
    }
}

/** 系统文件选择器从哪里开始：当前所在的内部存储目录。 */
private fun treeStart(path: String): Uri? {
    val rest =
        Regex("/data/media/\\d+(/.*)?")
            .matchEntire(SharedFolders.bindSource(path))
            ?.groupValues
            ?.get(1)
            ?.trim('/') ?: return null
    return DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$rest",
    )
}
