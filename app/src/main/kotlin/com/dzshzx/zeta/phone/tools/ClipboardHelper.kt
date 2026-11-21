package com.dzshzx.zeta.phone.tools

import android.content.ClipData
import android.os.IBinder
import android.os.PersistableBundle
import android.os.Process
import android.system.Os
import java.util.Base64
import kotlin.system.exitProcess

/**
 * 读写剪贴板的 root 辅助进程，不在 Zeta 的进程里运行：[ClipboardTools] 经 su 用 app_process 启动它，CLASSPATH 指向 Zeta 自己的
 * APK。
 *
 * Android 10 起只有前台（有焦点）的应用和默认输入法能读剪贴板，Zeta 多半在后台，无障碍服务也不算例外。 shell 身份带着
 * READ_CLIPBOARD_IN_BACKGROUND（adb 的测试就靠它），所以这里以 root 启动后降到 shell， 以 com.android.shell
 * 的名义调剪贴板服务。写也走这里：系统界面认 shell 写入时带的标记，不弹“已复制”的预览。
 *
 * 用法：`get` 读，打出 [TEXT]<base64 的 UTF-8 文字>、[EMPTY] 或 [OTHER]<说明>； `set` 从标准输入读要写入的 UTF-8
 * 文字（空的就清空），写好打出 [DONE]。失败打出 [ERROR]<原因>。 锁屏时系统一律当剪贴板是空的（读不到，写得进），这里分不出来，由调用方看锁屏状态。
 */
object ClipboardHelper {
    const val TEXT = "CLIP_TEXT:"
    const val EMPTY = "CLIP_EMPTY"
    const val OTHER = "CLIP_OTHER:"
    const val DONE = "CLIP_DONE"
    const val ERROR = "CLIP_ERR:"

    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    /** 系统界面的约定：shell 写入的剪贴板带上它就不弹预览。 */
    private const val SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

    // setuid 在应用里不该用，但这里正是在应用之外以 root 运行
    @Suppress("DEPRECATION")
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Process.myUid() == Process.ROOT_UID) Os.setuid(SHELL_UID)
            if (Process.myUid() != SHELL_UID) fail("needs root, uid=${Process.myUid()}")
            val clipboard = service()
            when (args.firstOrNull()) {
                "get" -> println(read(clipboard))
                "set" -> {
                    val text = System.`in`.readBytes().toString(Charsets.UTF_8)
                    if (text.isEmpty()) {
                        // 空的就是清空，和剪贴板本来什么都没有一样
                        invoke(clipboard, "clearPrimaryClip")
                    } else {
                        val clip = ClipData.newPlainText("Zeta", text)
                        clip.description.extras =
                            PersistableBundle().apply { putBoolean(SUPPRESS_OVERLAY, true) }
                        invoke(clipboard, "setPrimaryClip", clip)
                    }
                    println(DONE)
                }
                else -> fail("usage: get | set")
            }
            System.out.flush()
            exitProcess(0)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            println(ERROR + cause.toString().replace('\n', ' '))
            System.out.flush()
            exitProcess(1)
        }
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private fun service(): Any {
        val binder =
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as? IBinder ?: fail("no clipboard service")
        return Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: fail("no IClipboard")
    }

    /**
     * 调 IClipboard 的方法。各版本的参数不一样（后来加了 attributionTag、deviceId），按类型填： 剪贴板内容填
     * [clip]，第一个字符串填调用方包名，其余字符串 null，整数（用户、设备）都是 0。
     */
    private fun invoke(service: Any, name: String, clip: ClipData? = null): Any? {
        val method =
            service.javaClass.methods.firstOrNull { it.name == name } ?: fail("no IClipboard.$name")
        var strings = 0
        val arguments =
            method.parameterTypes.map { type ->
                when (type) {
                    ClipData::class.java -> clip
                    String::class.java -> if (strings++ == 0) SHELL_PACKAGE else null
                    Int::class.javaPrimitiveType -> 0
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        return method.invoke(service, *arguments.toTypedArray())
    }

    private fun read(clipboard: Any): String {
        val clip = invoke(clipboard, "getPrimaryClip") as? ClipData ?: return EMPTY
        val texts =
            (0 until clip.itemCount).mapNotNull { index ->
                val item = clip.getItemAt(index)
                item.text?.toString() ?: item.htmlText
            }
        if (texts.isNotEmpty()) {
            return TEXT +
                Base64.getEncoder()
                    .encodeToString(texts.joinToString("\n").toByteArray(Charsets.UTF_8))
        }
        if (clip.itemCount == 0) return EMPTY
        val item = clip.getItemAt(0)
        val what =
            item.uri?.let { "uri ${it.scheme}:…" } ?: item.intent?.let { "intent" } ?: "unknown"
        return OTHER +
            what +
            " (" +
            (0 until clip.description.mimeTypeCount).joinToString(",") {
                clip.description.getMimeType(it)
            } +
            ")"
    }
}
