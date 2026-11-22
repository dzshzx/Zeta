package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.compositionLocalWithComputedDefaultOf
import com.dzshzx.zeta.core.ConversationHub

/** 本进程的那个 hub：手机上 AndroidHubPlatform 建出或装上（测试）时登记，桌面的窗口启动时登记。 */
object ProcessHub {
    @Volatile var current: ConversationHub? = null
}

/** 界面用的中枢，深处的图片、侧栏、常用指令从这里取。外层没提供时就是本进程登记的那个（[ProcessHub]），所以手机的主界面、悬浮窗、 浏览器页和测试都不用各自提供。 */
val LocalHub =
    // 每次读时现取（不缓存）：测试里每个用例换一个 hub
    compositionLocalWithComputedDefaultOf<ConversationHub> {
        ProcessHub.current ?: error("本进程还没有登记 hub（见 ProcessHub）")
    }
