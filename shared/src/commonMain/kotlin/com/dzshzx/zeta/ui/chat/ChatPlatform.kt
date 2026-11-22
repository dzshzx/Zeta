package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ImageExport
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.platformService
import com.dzshzx.zeta.ui.pane.SessionExporter
import kotlinx.serialization.json.JsonObject

/**
 * 聊天页里碰 Android 的东西：手机工具、浏览器、按住说话等。实现是 app 的 ui/chat/AndroidChatPlatform.kt， 登记在 META-INF/services
 * 里。
 */
interface ChatPlatform {
    /** 手机工具 [tool]（`mcp__phone__` 后面那段）这一步的标题；认不出是 null，显示「手机 · 工具名」。 */
    fun phoneToolTitle(tool: String, input: JsonObject): String?

    /** 工具这一步标题行末尾的钮（手机上浏览器工具那步是「在浏览器里查看」）；没有就什么都不画。 */
    @Composable fun ToolStepAction(step: ToolStep)

    /** 回答下方复制键后面的朗读键；没有朗读就什么都不画。 */
    @Composable fun ReadAloudButton(answer: AnswerItem)

    /** 附件菜单里「图片」「文件」用的系统选择器：选好的图片交给 [onImages]（最多 [maxImages] 张），文件交给 [onFiles]。 */
    @Composable
    fun rememberFilePickers(
        maxImages: Int,
        onImages: (List<PickedFile>) -> Unit,
        onFiles: (List<PickedFile>) -> Unit,
    ): FilePickers

    /** 侧栏的导出（存成 .md 文件）和分享；多选导出选好了保存位置时调 [onSavedMany]。 */
    @Composable
    fun rememberSessionExporter(hub: ConversationHub, onSavedMany: () -> Unit): SessionExporter

    /**
     * 引用文件夹对话框里「用系统选择器选」：返回的函数从路径 [startPath] 打开选择器，选好的文件夹在 Linux 里的路径交给 [onPicked] （选的不在内部存储里是
     * null）。只有手机本机那台能引用手机上的文件夹。
     */
    @Composable
    fun rememberFolderTreePicker(onPicked: (String?) -> Unit): (startPath: String) -> Unit

    /** 对话所在的主机用不了时占满聊天页的说明（Debian 的检查、安装、root 提示）。 */
    @Composable
    fun Unavailable(
        hub: ConversationHub,
        padding: PaddingValues,
        onOpenInstall: () -> Unit,
        onOpenRemoteHosts: () -> Unit,
        onOpenSettings: () -> Unit,
    )

    /** 输入栏上方、这个对话独有的一条（手机上是后台屏幕：Claude 在后台开着的应用，点开看和接管）；没有就什么都不画。 */
    @Composable
    fun ConversationStrip(hub: ConversationHub, conversation: Conversation, modifier: Modifier)

    /** 全屏看图打开时对它的窗口做的设置（手机上铺进刘海区、系统栏图标用浅色）；没有要做的就什么都不做。 */
    @Composable fun ImmersiveImageWindow()

    /** 全屏看图底栏的「保存到相册」「分享」。 */
    @Composable fun rememberImageExporter(): ImageExporter

    /** 按住说话用的识别；手机上没有识别服务时是 null。 */
    @Composable fun rememberTalkInput(): TalkInput?

    companion object {
        val current: ChatPlatform by lazy {
            platformService<ChatPlatform>()
        }
    }
}

/** 打开系统的选择器（见 [ChatPlatform.rememberFilePickers]）。 */
interface FilePickers {
    fun pickImages()

    fun pickFiles()
}

/** 全屏看图里对一张图做的事（见 [ChatPlatform.rememberImageExporter]），做完自己提示成没成。 */
interface ImageExporter {
    fun save(image: ImageExport)

    fun share(image: ImageExport)
}
