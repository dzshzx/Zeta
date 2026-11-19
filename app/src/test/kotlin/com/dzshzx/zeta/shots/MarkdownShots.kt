package com.dzshzx.zeta.shots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import io.github.suqi8.coui.kmp.squircle.LocalSquircleEnabled
import org.junit.Test

/**
 * 聊天里的 Markdown：标题、列表、行内样式、代码块、引用；表格、提示块、公式、分隔线。一屏放不下，分两张。
 * 平滑圆角（squircle）靠着色器裁剪，Robolectric 里画不出来，代码块、表格、提示块会整块空白；
 * 这里关掉它，退回普通圆角，好看清块里的内容和配色。
 */
class MarkdownShots : ShotTest() {
    @Composable
    private fun Chat(hub: ConversationHub) {
        CompositionLocalProvider(LocalSquircleEnabled provides false) {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }

    @Test
    fun blocks() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("Markdown 样张", listOf(UserItem("u1", "把构建步骤写成清单"), AnswerItem("a1", blocks, streaming = false)))
        shoot("markdown-blocks") { Chat(hub) }
    }

    @Test
    fun tables() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("Markdown 样张", listOf(UserItem("u1", "对比一下两种做法"), AnswerItem("a1", tables, streaming = false)))
        shoot("markdown-tables") { Chat(hub) }
    }

    private val blocks = """
        ## 构建步骤

        先确认 **JDK 25** 已装好，再用 `tools/gradle` 构建，参见 [CLAUDE.md](https://example.invalid/claude)。

        1. 拉取最新代码
        2. 编译：
           - 正式版 `assembleDebug`
           - 测试版 `assembleDev`
        3. 跑截图测试

        - [x] 编译通过
        - [ ] 装到手机

        ```kotlin
        fun main() {
            println("hello, Zeta")
        }
        ```

        > 引用：改界面前先跑一次截图，改完再跑，看对照图。
        >
        > > 嵌套的引用只画一条细线。
    """.trimIndent()

    private val tables = """
        ### 两种做法

        | 做法 | 内存 | 说明 |
        | :--- | :---: | ---: |
        | 进程内编译 | 低 | 手机上用 |
        | 守护进程 | 高 | 工作站用 |

        > [!NOTE]
        > 截图测试不提交基准图。

        > [!WARNING]
        > 不要卸载重装，会丢掉全部设置。

        行内公式 ${'$'}E = mc^2${'$'}，独立公式：

        ${'$'}${'$'}
        \sum_{i=1}^{n} i = \frac{n(n+1)}{2}
        ${'$'}${'$'}

        ---

        > [!TIP]
        > 分隔线上下留白相等。
    """.trimIndent()
}
