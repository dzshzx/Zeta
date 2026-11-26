package com.dzshzx.zeta.shots

import androidx.compose.foundation.text.input.TextFieldState
import org.junit.Test

/** 输入框里的 Markdown 显示样式：标记符调淡，内容按加粗、斜体、代码等显示。 */
class ComposerMarkdownShots : ShotTest() {
    @Test
    fun inline() =
        shootComposer(
            "composer-markdown-inline",
            TextFieldState(
                "## 改一下登录页\n帮我把 **超时时间** 改成 *30 秒*，不要动 `retry_count`\n" +
                    "- 旧逻辑 ~~每次重连~~ 删掉\n> 参考上周的讨论",
            ),
        )

    @Test
    fun codeBlock() =
        shootComposer(
            "composer-markdown-code",
            TextFieldState("这段为什么报错：\n```go\nfunc main() {\n    fmt.Println(x)\n}\n```"),
        )
}
