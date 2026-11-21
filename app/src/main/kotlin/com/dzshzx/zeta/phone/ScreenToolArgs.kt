package com.dzshzx.zeta.phone

import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonObject

/** 操作屏幕的工具的参数核对和系统命令输出的解析：不碰 Android，单元测试在 ScreenToolArgsTest。 */

/** 参数核对的结果：对了是 [value]，不对是给 Claude 的一句 [problem]。 */
internal class Checked<T>(val value: T?, val problem: String?) {
    companion object {
        fun <T> ok(value: T) = Checked(value, null)

        fun <T> bad(problem: String) = Checked<T>(null, problem)
    }
}

/** 点按、长按、输入、滚动的目标：控件编号、按文字和 id 找、或坐标。都没给是 [Unset]。 */
internal sealed interface ControlTarget {
    data class Index(val index: Int) : ControlTarget

    data class Find(val selector: ControlSelector) : ControlTarget

    data class Point(val x: Int, val y: Int) : ControlTarget

    data object Unset : ControlTarget

    companion object {
        /** [allowPoint]：这个工具认不认 x、y。几种一起给时按 node、match/id、x/y 的先后取一种（和原来只认 node 时一样宽容）。 */
        fun parse(a: JsonObject, allowPoint: Boolean): Checked<ControlTarget> {
            val index = a.int("node")
            val selector = ControlSelector.of(a.str("match"), a.str("id"))
            val x = if (allowPoint) a.int("x") else null
            val y = if (allowPoint) a.int("y") else null
            return when {
                index != null -> Checked.ok(Index(index))
                selector != null -> Checked.ok(Find(selector))
                x != null && y != null -> Checked.ok(Point(x, y))
                x != null || y != null -> Checked.bad("x 和 y 要一起给")
                else -> Checked.ok(Unset)
            }
        }
    }
}

/** wait 要等什么。 */
internal sealed interface WaitRequest {
    /** 干等 [ms] 毫秒。 */
    data class Time(val ms: Long) : WaitRequest

    /** 等对得上 [selector] 的控件出现（[gone] 时等它消失）。 */
    data class Control(val selector: ControlSelector, val gone: Boolean, val timeoutMs: Long) :
        WaitRequest

    /** 等 [app]（包名或应用名）到当前屏幕的最前面。 */
    data class App(val app: String, val timeoutMs: Long) : WaitRequest

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        fun parse(a: JsonObject): Checked<WaitRequest> {
            val selector = ControlSelector.of(a.str("match"), a.str("id"))
            val app = a.str("app")?.trim()?.takeIf { it.isNotEmpty() }
            val gone = a.bool("gone") == true
            val timeout =
                (a.int("timeout_ms")?.toLong() ?: DEFAULT_TIMEOUT_MS).coerceIn(500, 60_000)
            return when {
                selector != null && app != null -> Checked.bad("match/id 和 app 只能等一样")
                selector != null -> Checked.ok(Control(selector, gone, timeout))
                gone -> Checked.bad("gone 要和 match 或 id 一起用")
                app != null -> Checked.ok(App(app, timeout))
                else ->
                    Checked.ok(
                        Time((a.int("duration_ms")?.toLong() ?: 1000L).coerceIn(100, 30_000)),
                    )
            }
        }
    }
}

/** open_app 要打开什么：应用（[app]），或者交给系统的 intent（[action] 加上数据 [uri]，可以限定由 [app] 处理）。 */
internal class OpenRequest(val app: String?, val uri: String?, val action: String?) {
    /** 走 intent，而不是从桌面图标启动。 */
    val isIntent: Boolean
        get() = uri != null || action != null

    /** 实际用的 intent 动作：给了 uri 没给动作就是 VIEW。 */
    val intentAction: String
        get() = action ?: VIEW

    companion object {
        const val VIEW = "android.intent.action.VIEW"
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+")
        private val ACTION = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun parse(a: JsonObject): Checked<OpenRequest> {
            val app = a.str("app")?.trim()?.takeIf { it.isNotEmpty() }
            val uri = a.str("uri")?.trim()?.takeIf { it.isNotEmpty() }
            val action = a.str("action")?.trim()?.takeIf { it.isNotEmpty() }
            if (app == null && uri == null && action == null)
                return Checked.bad("要给 app（应用名或包名）、uri 或 action 之一")
            if (uri != null && (!SCHEME.matches(uri) || uri.any { it == '\n' || it == '\u0000' })) {
                return Checked.bad("uri 要带 scheme，比如 https://…、tel:…、geo:…，或应用自己的 deep link")
            }
            if (action != null && !ACTION.matches(action))
                return Checked.bad("action 要是完整的 intent 动作名，比如 android.settings.WIFI_SETTINGS")
            return Checked.ok(OpenRequest(app, uri, action))
        }

        /**
         * 只在主屏上打开、不放到后台屏幕的包：系统默认的桌面 [home]（问不到时为 null）、Zeta 自己（[self]）、系统界面， 和系统本身（选择打开方式的界面也在里面）。
         */
        fun mainOnly(home: String?, self: String): Set<String> =
            setOfNotNull("android", "com.android.systemui", self, home)
    }
}

/** 系统里的一个任务栈（`am stack list` 的一段）。[topActivity] 是最上面的界面，`包名/类名`。 */
internal class RootTask(
    val id: Int,
    val displayId: Int,
    val packageName: String?,
    val visible: Boolean,
    val topActivity: String?,
)

internal object SystemOutput {
    private val ROOT_TASK = Regex("^RootTask id=(\\d+) .*displayId=(-?\\d+)")
    private val TASK = Regex("^\\s+taskId=\\d+: ([^/\\s]+)/\\S* .*visible=(true|false)")
    private val TOP = Regex("topActivity=ComponentInfo\\{([^}\\s]+)\\}")

    /** 解析 `am stack list`：最上层的在前。 */
    fun rootTasks(out: String): List<RootTask> {
        val list = ArrayList<RootTask>()
        var id = -1
        var displayId = -1
        var packageName: String? = null
        var visible = false
        var top: String? = null
        fun flush() {
            if (id >= 0) list += RootTask(id, displayId, packageName, visible, top)
        }
        for (line in out.lineSequence()) {
            val head = ROOT_TASK.find(line)
            if (head != null) {
                flush()
                id = head.groupValues[1].toInt()
                displayId = head.groupValues[2].toInt()
                packageName = null
                visible = false
                top = null
                continue
            }
            val task = TASK.find(line) ?: continue
            if (packageName == null) {
                packageName = task.groupValues[1]
                top = TOP.find(line)?.groupValues?.get(1)
            }
            if (task.groupValues[2] == "true") visible = true
        }
        flush()
        return list
    }

    /** `包名/包名.ui.Main` 缩成 `包名/.ui.Main`，和 am、dumpsys 的写法一样。 */
    fun shortComponent(component: String): String {
        val packageName = component.substringBefore('/')
        val cls = component.substringAfter('/', "")
        return if (cls.startsWith("$packageName.")) "$packageName/" + cls.removePrefix(packageName)
        else component
    }

    /**
     * 解析 `cmd package resolve-activity --brief`：最后一行是 `包名/类名`；没有应用能处理时是 “No activity found”，返回
     * null。 有几个应用都能处理、没设默认时，结果是系统的选择界面。
     */
    fun resolvedComponent(out: String): String? =
        out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.takeIf { '/' in it && ' ' !in it && '=' !in it }

    /** 系统的“选择用哪个应用打开”界面（几个应用都能处理、没设默认时）。 */
    fun isChooser(component: String): Boolean {
        val packageName = component.substringBefore('/')
        return packageName == "android" ||
            packageName == "com.android.intentresolver" ||
            component.endsWith("ResolverActivity")
    }
}

/** 填完输入框读回来的内容对不对得上：一样就算对；要清空时，读回来是空的或者只剩提示文字（有的输入框空着时把提示当文字报）也算对。 */
internal fun textLanded(expected: String, actual: String?, hint: String?): Boolean {
    val now = actual.orEmpty()
    if (now == expected) return true
    return expected.isEmpty() && (now.isEmpty() || now == hint)
}
