package com.dzshzx.zeta.core.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.ScreenControls
import com.dzshzx.zeta.phone.ZetaAccessibilityService
import kotlin.concurrent.thread

/**
 * 列出某块屏上看得见、带文字或说明的控件，给 tools/vd 的 nodes、tap-text 用（设备脚本按控件上的字点按）。 经 Zeta 自己的无障碍服务读，含 Zeta
 * 自己的悬浮层（顶部胶囊、结果卡片）：它们是无障碍服务画的窗口，系统不报给别的读取方， uiautomator 读不到。
 *
 * 控件按 [ScreenControls.visible] 的先后（最上层的窗口在前）；一行一个：中心 x、y（这块屏上的坐标）、文字、说明，以制表符分隔（见 [line]）。
 * 清单放在广播的结果数据里交回，结果码 0 是读好了，1 是无障碍服务连不上，2 是读的时候出错，这两种 data 里是说明。 只在测试版的 manifest 里登记
 * （app/src/debug），不导出，只有 root、system 发得进来： am broadcast -n
 * com.dzshzx.zeta.dev/com.dzshzx.zeta.core.diag.UiNodes --ei display <屏号>
 */
class UiNodes : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val display = intent.getIntExtra("display", 0)
        val pending = goAsync()
        thread(name = "zeta-ui-nodes", isDaemon = true) {
            try {
                val (code, data) = list(context, display)
                pending.setResult(code, data, null)
            } catch (e: Exception) {
                pending.setResult(2, e.toString(), null)
            } finally {
                pending.finish()
            }
        }
    }

    private fun list(context: Context, display: Int): Pair<Int, String> {
        val service =
            ZetaAccessibilityService.instance
                ?: ZetaAccessibilityService.instance.takeIf {
                    AccessibilityGuard.enable(context, "设备脚本读控件")
                }
                ?: return 1 to "Zeta 测试版的无障碍服务连不上"
        service.dropCache()
        val lines =
            ScreenControls.visible(service.roots(display, withOwnOverlays = true)).mapNotNull {
                val text = it.facts.text.orEmpty()
                val desc = it.facts.description.orEmpty()
                if (text.isEmpty() && desc.isEmpty()) null
                else line(it.bounds.centerX(), it.bounds.centerY(), text, desc)
            }
        return 0 to lines.joinToString("") { "$it\n" }
    }

    companion object {
        /** 清单里的一行：文字和说明里的换行、制表符换成空格，一个控件总是一行四列。 */
        fun line(x: Int, y: Int, text: String, desc: String): String {
            fun flat(s: String) = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            return "$x\t$y\t${flat(text)}\t${flat(desc)}"
        }
    }
}
