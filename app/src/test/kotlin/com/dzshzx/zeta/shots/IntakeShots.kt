package com.dzshzx.zeta.shots

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.isDialog
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileLimits
import com.dzshzx.zeta.core.Intake
import com.dzshzx.zeta.core.IntakeSource
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.chat.IntakeChooser
import com.dzshzx.zeta.ui.chat.IntakeProblemsDialog
import com.dzshzx.zeta.ui.chat.IntakeSink
import io.github.suqi8.coui.kmp.basic.Scaffold
import org.junit.Test

/** 截屏发给 Claude、用 Zeta 打开文件：「放进哪个对话」、放进输入栏的样子、没收的文件的说明。 */
class IntakeShots : ShotTest() {
    /** 当前对话有内容时截了一张屏：问放进新对话还是接着这个。它盖在所有页面上，是单独的窗口，只截这个窗口。 */
    @Test
    fun chooser() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("看看这个报错", chat)
        hub.pendingIntake = Intake(IntakeSource.Screenshot, images = listOf(screenshot()))
        shoot("intake-chooser", target = isDialog()) {
            Box {
                ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
                IntakeChooser(hub)
            }
        }
    }

    /** 定了放进来：分享来的文字进输入框，截屏成图片附件。 */
    @Test
    fun composer() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("看看这个报错", chat)
        // 输入栏的内容是这个对话的草稿，两种配色各从头组合一遍时还在：只送一次
        hub.incoming = Intake(IntakeSource.Share, text = "页面标题\nhttps://example.com/article", images = listOf(screenshot()))
        shoot("intake-composer") {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }

    /** 太大的文件没有复制：说明原因和该怎么办。 */
    @Test
    fun problems() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("看看这个报错", chat)
        // 页面上的对话框要在 COUI 的 Scaffold 里才画得出来（真用时它在聊天页里面）
        shoot("intake-problems") {
            Scaffold {
                Box {
                    ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
                    Problems(hub)
                }
            }
        }
    }

    @Composable
    private fun Problems(hub: ConversationHub) {
        val scope = rememberCoroutineScope()
        val sink = remember {
            IntakeSink(hub, hub.context, scope, TextFieldState(), mutableStateListOf(), mutableStateListOf(), maxImages = 4).also {
                it.force(
                    "problems",
                    listOf(
                        FileLimits.tooLargeReason("录屏-1118.mp4", 1288490189L),
                        "一次最多收 ${FileLimits.MAX_FILES} 个文件，另外 3 个没有加进来",
                    ),
                )
                it.force("rejectedLarge", true)
            }
        }
        IntakeProblemsDialog(sink)
    }

    private fun screenshot(): Attachment {
        val bitmap = Bitmap.createBitmap(305, 664, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3A6EA5.toInt()) }
        return Attachment("shot", bitmap.asImageBitmap(), "image/jpeg", "")
    }

    private val chat = listOf(
        UserItem("u1", "构建一直失败，帮我看看"),
        AnswerItem("a1", "把报错的那一屏发给我看看，最好连同上面几行。", streaming = false),
    )
}
