package com.dzshzx.zeta.core.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.ZetaAccessibilityService
import java.io.File
import kotlin.concurrent.thread

/**
 * 把某块屏上看得见、带文字或说明的控件写成一份清单，给 tools/vd 的 nodes、tap-text 用（设备脚本按控件上的字点按）。 经 Zeta 自己的无障碍服务读，含 Zeta
 * 自己的悬浮层（顶部胶囊、结果卡片）：它们是无障碍服务画的窗口，系统不报给别的读取方， uiautomator 读不到。
 *
 * 窗口从最上层往下，每个窗口里按树的先序；一行一个控件：中心 x、y（这块屏上的坐标）、文字、说明，以制表符分隔（见 [line]）。 写到应用私有目录的
 * files/ui-nodes.tsv；广播的结果码 0 是写好了，1 是无障碍服务连不上，2 是读的时候出错，data 里是说明。 只在测试版的 manifest
 * 里登记（app/src/debug），不导出，只有 root、system 发得进来： am broadcast -n
 * com.dzshzx.zeta.dev/com.dzshzx.zeta.core.diag.UiNodes --ei display <屏号>
 */
class UiNodes : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val display = intent.getIntExtra("display", 0)
        // 发广播的一方带上发出时刻（--el sent <毫秒>），回执里报广播在路上走了多久、读控件用了多久
        val sent = intent.getLongExtra("sent", 0L)
        val received = System.currentTimeMillis()
        val pending = goAsync()
        thread(name = "zeta-ui-nodes", isDaemon = true) {
            try {
                val (code, data) = dump(context, display)
                val read = System.currentTimeMillis() - received
                val queued = if (sent > 0) "广播送达 ${received - sent} ms，" else ""
                pending.setResult(code, "$data（${queued}读控件 $read ms）", null)
            } catch (e: Exception) {
                pending.setResult(2, e.toString(), null)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dump(context: Context, display: Int): Pair<Int, String> {
        val service =
            ZetaAccessibilityService.instance
                ?: ZetaAccessibilityService.instance.takeIf {
                    AccessibilityGuard.enable(context, "设备脚本读控件")
                }
                ?: return 1 to "Zeta 测试版的无障碍服务连不上"
        service.dropCache()
        val lines = mutableListOf<String>()
        service.roots(display, withOwnOverlays = true).forEach { walk(it, lines) }
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(lines.joinToString("") { "$it\n" })
        if (!tmp.renameTo(File(context.filesDir, FILE))) return 2 to "写不了 $FILE"
        return 0 to "${lines.size} 个控件"
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (node.isVisibleToUser) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                out += line(r.centerX(), r.centerY(), text, desc)
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out) }
    }

    companion object {
        /** 结果文件，在应用私有目录的 files/ 下。 */
        const val FILE = "ui-nodes.tsv"

        /** 清单里的一行：文字和说明里的换行、制表符换成空格，一个控件总是一行四列。 */
        fun line(x: Int, y: Int, text: String, desc: String): String {
            fun flat(s: String) = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
            return "$x\t$y\t${flat(text)}\t${flat(desc)}"
        }
    }
}
