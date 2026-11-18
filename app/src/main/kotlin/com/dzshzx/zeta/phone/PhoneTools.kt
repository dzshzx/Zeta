package com.dzshzx.zeta.phone

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.util.Base64
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 交给 Claude Code 的“手机”工具：读屏、点按、输入、开应用、在 Android 侧以 root 执行命令。
 *
 * 它是一个 MCP 服务器，但不开端口：Claude Code 把 MCP 消息夹在本来就有的控制通道里发过来
 * （`--mcp-config` 里声明 `{"type":"sdk"}`），[handle] 处理完再原路送回去。
 *
 * 坐标一律用“截图坐标”：屏幕按比例缩到长边不超过 [MAX_EDGE] 之后的像素。
 * 这样模型看到的截图、控件列表里的位置、它填的点击位置是同一套数，不用换算。
 *
 * 每个会话一份（由 ConversationHub 按会话登记）：应用默认开在这个会话自己的后台屏幕（[VirtualScreen]）上，不占用主屏，
 * 几个对话同时操作手机也互不干扰。工具作用在“当前屏幕”上：最近一次打开应用的那块。
 * 对话的进程结束时后台屏幕留着（[detach]，让它休眠），重新打开会话、接着发送时还是那块屏和上面的应用。
 * 一轮结束后 [IDLE_SLEEP_MS] 没再用也休眠：屏幕关掉，应用停下但任务和页面都在，要用时自动唤醒。
 * 用户在界面上关掉、全应用屏幕太多被腾掉、或 [destroy] 时才释放。
 */
class PhoneTools(
    private val context: Context,
    private val settings: Settings,
    /** 要新建后台屏幕之前调用（工具线程上，持着本对象的锁）：全应用的后台屏幕有上限，满了由登记处先腾出一块。 */
    private val beforeCreate: (PhoneTools) -> Unit = {},
    /** 后台屏幕没了（在主线程上调用）：登记处据此丢掉没有屏、也没有对话在用的那些。 */
    private val onScreenGone: () -> Unit = {},
) {
    /** 对话是否正在进行（空闲释放、腾地方时要避开）。由正在用它的对话经 [attach] 给出。 */
    @Volatile private var busy: () -> Boolean = { false }

    /** 后台屏幕有了、没了或换了应用（在主线程上调用）。 */
    @Volatile private var onChanged: () -> Unit = {}

    /** [destroy] 过了：不再建后台屏幕，免得还没结束的工具调用给已经没人要的会话建出一块没人管的屏。 */
    @Volatile private var destroyed = false

    private class Screen(val width: Int, val height: Int) {
        val scale: Float = minOf(1f, MAX_EDGE / max(width, height).toFloat())
        fun toModel(v: Int): Int = (v * scale).roundToInt()
        fun toScreen(v: Int): Int = (v / scale).roundToInt()
        val modelWidth get() = toModel(width)
        val modelHeight get() = toModel(height)
    }

    private class Seen(val node: AccessibilityNodeInfo, val bounds: Rect)

    /** 上一次列出来的控件，供按编号操作。 */
    private var seen: List<Seen> = emptyList()
    private val lock = Any()

    /** 主屏的编号。平时是 0；调试时可以经设置指到别的屏上，免得测试打扰正在用手机的人。 */
    private val mainDisplay: Int get() = settings.phoneDisplay

    private val main = Handler(Looper.getMainLooper())

    /** 这个对话的后台屏幕（界面用，只在主线程上改）。 */
    var background by mutableStateOf<VirtualScreen?>(null)
        private set

    /** 后台屏幕上最近打开的应用名（界面用）。 */
    var backgroundApp by mutableStateOf<String?>(null)
        private set

    /** 工具线程看到的后台屏幕。 */
    @Volatile private var screenRef: VirtualScreen? = null

    /** 当前操作的是后台屏幕。后台屏幕没了也保持为 true，直到模型换屏，免得它不知情地点到主屏上。 */
    @Volatile private var onBackground = false

    @Volatile private var lastUse = 0L

    /** 这次调用前刚把休眠的后台屏幕唤醒：在观察结果里提一句（在 [lock] 里用）。 */
    private var justWoke = false

    /** 为后台运行而禁掉音频焦点的应用，及其原来的设置。 */
    private val muted = LinkedHashMap<String, String>()

    private val display: Int get() = if (onBackground) screenRef?.displayId ?: Display.INVALID_DISPLAY else mainDisplay

    /** 当前屏幕是后台屏幕但它已经没了：返回给模型的说明。 */
    private fun lostBackground(): String? =
        if (onBackground && screenRef?.alive != true) {
            "后台屏幕已经关掉了（用户在 Zeta 里关的，或者同时开着的后台屏幕太多、这块被腾掉了）。要继续就用 open_app 重新打开应用；要看主屏用 screen 并传 display: main。"
        } else null

    /** 要操作当前屏幕之前：后台屏幕没了返回说明；在休眠就先唤醒（等到新画面出来）。 */
    private fun ready(): String? {
        lostBackground()?.let { return it }
        if (!onBackground) return null
        val screen = screenRef ?: return null
        return wakeIfAsleep(screen)
    }

    /** 后台屏幕在休眠就唤醒它。返回问题，没有问题返回 null。 */
    private fun wakeIfAsleep(screen: VirtualScreen): String? {
        if (!screen.asleep.value) return null
        if (!screen.wake()) return if (screen.alive) "后台屏幕在休眠，没能唤醒。可以再试一次，或者用 open_app 重新打开应用" else lostBackground()
        // 第一帧出来了，再稍等让无障碍那边的窗口列表也跟上
        Thread.sleep(200)
        justWoke = true
        return null
    }

    // ---- MCP ----

    /** 处理一条 MCP 消息，返回要送回去的应答（通知类消息没有应答，返回空对象）。 */
    fun handle(message: JsonObject): JsonObject {
        val id = message["id"] ?: return JsonObject(emptyMap())
        lastUse = System.currentTimeMillis()
        val result: JsonObject? = when (message.str("method")) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", message.obj("params")?.str("protocolVersion") ?: "2025-06-18")
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", SERVER); put("version", "1") }
                put("instructions", INSTRUCTIONS)
            }
            "tools/list" -> buildJsonObject { put("tools", TOOLS) }
            "tools/call" -> {
                val params = message.obj("params")
                val name = params?.str("name").orEmpty()
                val arguments = params?.obj("arguments") ?: JsonObject(emptyMap())
                runCatching { call(name, arguments) }.getOrElse { failure(it.message ?: it.javaClass.simpleName) }
            }
            "ping" -> JsonObject(emptyMap())
            else -> null
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result != null) put("result", result) else putJsonObject("error") { put("code", -32601); put("message", "Method not found") }
        }
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", value) }) }
    }

    private fun failure(value: String): JsonObject = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", value) }) }
        put("isError", true)
    }

    private fun call(name: String, a: JsonObject): JsonObject = when (name) {
        "shell" -> shell(a)
        "find_apps" -> findApps(a.str("query").orEmpty())
        else -> synchronized(lock) {
            justWoke = false
            when (name) {
                "screen" -> {
                    // 切到后台屏幕时也在 ready() 里唤醒
                    val problem = a.str("display")?.let { switchTo(it) } ?: ready()
                    if (problem != null) failure(problem)
                    else observe(screenshot = a.bool("screenshot") == true, maxNodes = (a.int("max_nodes") ?: 80).coerceIn(10, 250))
                }
                "tap" -> act(a) { screen -> tap(screen, a, longPressMs = null) }
                "long_press" -> act(a) { screen -> tap(screen, a, longPressMs = (a.int("duration_ms") ?: 700).coerceIn(300, 5000).toLong()) }
                "swipe" -> act(a) { screen -> swipe(screen, a) }
                "type_text" -> act(a) { typeText(a) }
                "press_key" -> act(a) { pressKey(a.str("key").orEmpty()) }
                "open_app" -> openApp(a)
                "show_to_user" -> showToUser(a)
                else -> failure("没有这个工具：$name")
            }
        }
    }

    /** screen 的 display 参数：换到主屏或后台屏幕。返回问题，没有问题返回 null。 */
    private fun switchTo(target: String): String? = when (target.lowercase()) {
        "main" -> {
            onBackground = false
            null
        }
        "background" -> if (screenRef?.alive == true) {
            onBackground = true
            null
        } else "现在没有后台屏幕：用 open_app 打开应用时会自动建一块"
        else -> "display 只能是 main 或 background"
    }

    // ---- 读屏 ----

    private fun screen(): Screen? {
        if (onBackground) return screenRef?.takeIf { it.alive }?.let { Screen(it.width, it.height) }
        val manager = context.getSystemService(DisplayManager::class.java)
        manager?.getDisplay(display)?.let { d ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(metrics)
            if (metrics.widthPixels > 0) return Screen(metrics.widthPixels, metrics.heightPixels)
        }
        // 别的进程建的虚拟屏应用自己看不到，问系统
        val out = root("wm size -d $display").stdout
        val size = Regex("(\\d+)x(\\d+)").findAll(out).lastOrNull() ?: return null
        return Screen(size.groupValues[1].toInt(), size.groupValues[2].toInt())
    }

    private fun service(): ZetaAccessibilityService? {
        ZetaAccessibilityService.instance?.let { return it }
        // 开关开着但服务没连上（刚装好、被系统断开）：用 root 把它打开
        if (!enableAccessibility(context)) return null
        return ZetaAccessibilityService.instance
    }

    private fun stateLine(screen: Screen): String {
        val power = context.getSystemService(PowerManager::class.java)
        val guard = context.getSystemService(KeyguardManager::class.java)
        val other = screenRef?.takeIf { it.alive }
        val notes = buildList {
            if (!onBackground && display == 0 && power?.isInteractive == false) add("屏幕是关着的（可以用 shell 执行 input keyevent WAKEUP）")
            if (!onBackground && display == 0 && guard?.isKeyguardLocked == true) add("手机处于锁屏状态")
            if (!onBackground && other != null) {
                add("后台屏幕上还开着" + (backgroundApp?.let { " $it" } ?: "应用") + (if (other.asleep.value) "，在休眠" else "") + "（screen 传 display: background 切过去）")
            }
        }
        val where = if (onBackground) "后台屏幕（display $display，用户看不到，除非在 Zeta 里打开查看）" else "主屏"
        return "$where ${screen.modelWidth}×${screen.modelHeight}（截图坐标）" + notes.joinToString("") { "；$it" }
    }

    private fun describe(screen: Screen, maxNodes: Int): String {
        val service = service() ?: run {
            seen = emptyList()
            return stateLine(screen) + "\n无障碍服务没有开启，读不到控件列表（只能靠截图和坐标操作）。请用户到 Zeta 的 设置 → 手机操作 里开启。"
        }
        val found = ArrayList<Seen>()
        val lines = ArrayList<String>()
        var truncated = false
        var packageName: String? = null
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60) return
            if (!node.isVisibleToUser) return
            val label = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val interesting = label.isNotEmpty() || description.isNotEmpty() || node.isClickable || node.isLongClickable ||
                node.isEditable || node.isScrollable || node.isCheckable
            if (interesting) {
                if (found.size >= maxNodes) {
                    truncated = true
                    return
                }
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    if (packageName == null) packageName = node.packageName?.toString()
                    val flags = buildList {
                        if (node.isClickable) add("可点")
                        if (node.isLongClickable) add("可长按")
                        if (node.isEditable) add("可输入")
                        if (node.isScrollable) add("可滚动")
                        if (node.isCheckable) add(if (node.isChecked) "已勾选" else "未勾选")
                        if (node.isSelected) add("已选中")
                        if (node.isFocused) add("有焦点")
                        if (!node.isEnabled) add("不可用")
                        if (node.isPassword) add("密码")
                    }
                    lines += buildString {
                        append('[').append(found.size).append("] ")
                        append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                        if (label.isNotEmpty()) append(" \"").append(clip(label)).append('"')
                        if (description.isNotEmpty() && description != label) append(" 描述=\"").append(clip(description)).append('"')
                        node.viewIdResourceName?.substringAfterLast('/')?.let { append(" id=").append(it) }
                        append(" (").append(screen.toModel(bounds.centerX())).append(',').append(screen.toModel(bounds.centerY())).append(')')
                        if (flags.isNotEmpty()) append(' ').append(flags.joinToString("·"))
                    }
                    found += Seen(node, bounds)
                }
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }
        for (root in service.roots(display)) visit(root, 0)
        seen = found
        return buildString {
            append(stateLine(screen))
            packageName?.let { append("；前台应用 ").append(it) }
            append('\n')
            if (lines.isEmpty()) append("（没有读到控件。可能是游戏、视频这类自绘界面，用 screenshot=true 看截图。）")
            else append(lines.joinToString("\n"))
            if (truncated) append("\n…控件太多，只列了前 ").append(maxNodes).append(" 个（可以调大 max_nodes，或滚动后再看）")
        }
    }

    private fun clip(value: String): String = value.replace('\n', ' ').let { if (it.length > 80) it.take(80) + "…" else it }

    private fun observe(screenshot: Boolean, maxNodes: Int, notes: List<String> = emptyList()): JsonObject {
        val woke = if (justWoke) listOf("后台屏幕刚从休眠中唤醒（应用和页面都还在）") else emptyList()
        justWoke = false
        lostBackground()?.let { return failure((woke + notes + it).joinToString("\n")) }
        val screen = screen() ?: return failure("找不到编号为 $display 的屏幕")
        val tree = (woke + notes + describe(screen, maxNodes)).joinToString("\n")
        if (!screenshot) return text(tree)
        val image = capture(screen) ?: return text("$tree\n（截图失败）")
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", tree) })
                add(buildJsonObject { put("type", "image"); put("data", image); put("mimeType", "image/jpeg") })
            }
        }
    }

    /** 截图，缩到“截图坐标”的大小，JPEG，base64。 */
    private fun capture(screen: Screen): String? {
        val raw: Bitmap = if (onBackground) screenRef?.capture() ?: return null
        else ZetaAccessibilityService.instance?.screenshot(display) ?: run {
            // 没有无障碍服务时，主屏可以经 root 截
            if (display != 0) return null
            val process = runCatching { ProcessBuilder("su", "-c", "exec screencap -p").start() }.getOrNull() ?: return null
            val bytes = runCatching { process.inputStream.readBytes() }.getOrNull()
            process.waitFor()
            bytes?.takeIf { it.isNotEmpty() }?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        }
        val scaled = if (screen.scale < 1f) Bitmap.createScaledBitmap(raw, screen.modelWidth, screen.modelHeight, true) else raw
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---- 操作 ----

    /** 做一个动作，稍等界面稳定，然后把新的控件列表一并交回去（省得模型每次都再问一遍）。 */
    private fun act(a: JsonObject, settleMs: Long = 500, action: (Screen) -> String?): JsonObject {
        ready()?.let { return failure(it) }
        val screen = screen() ?: return failure("找不到编号为 $display 的屏幕")
        val error = action(screen)
        if (error != null) return failure(error)
        Thread.sleep(settleMs)
        return observe(screenshot = a.bool("screenshot") == true, maxNodes = 60)
    }

    /** 要点的位置（屏幕像素）：给了控件编号就取它的中心，否则取坐标。 */
    private fun target(screen: Screen, a: JsonObject): Pair<Int, Int>? {
        a.int("node")?.let { index -> seen.getOrNull(index)?.let { return it.bounds.centerX() to it.bounds.centerY() } ?: return null }
        val x = a.int("x") ?: return null
        val y = a.int("y") ?: return null
        return screen.toScreen(x) to screen.toScreen(y)
    }

    private fun tap(screen: Screen, a: JsonObject, longPressMs: Long?): String? {
        val index = a.int("node")
        if (index != null) {
            val item = seen.getOrNull(index) ?: return "没有编号 $index 的控件，先用 screen 重新看一下屏幕"
            // 控件自己声明可点，就让它自己处理，比模拟触摸可靠
            val nodeAction = if (longPressMs == null) AccessibilityNodeInfo.ACTION_CLICK else AccessibilityNodeInfo.ACTION_LONG_CLICK
            val able = if (longPressMs == null) item.node.isClickable else item.node.isLongClickable
            if (able && runCatching { item.node.performAction(nodeAction) }.getOrDefault(false)) return null
        }
        val (x, y) = target(screen, a) ?: return "要给出 node（控件编号），或者 x 和 y"
        val service = ZetaAccessibilityService.instance
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            if (service.gesture(display, path, longPressMs ?: 60)) return null
        }
        val command = if (longPressMs == null) "input -d $display tap $x $y" else "input -d $display swipe $x $y $x $y $longPressMs"
        return rootError(command)
    }

    private fun swipe(screen: Screen, a: JsonObject): String? {
        val x1 = a.int("x1") ?: return "缺少 x1"
        val y1 = a.int("y1") ?: return "缺少 y1"
        val x2 = a.int("x2") ?: return "缺少 x2"
        val y2 = a.int("y2") ?: return "缺少 y2"
        val duration = (a.int("duration_ms") ?: 300).coerceIn(50, 5000).toLong()
        val sx1 = screen.toScreen(x1); val sy1 = screen.toScreen(y1); val sx2 = screen.toScreen(x2); val sy2 = screen.toScreen(y2)
        val service = ZetaAccessibilityService.instance
        if (service != null) {
            val path = Path().apply { moveTo(sx1.toFloat(), sy1.toFloat()); lineTo(sx2.toFloat(), sy2.toFloat()) }
            if (service.gesture(display, path, duration)) return null
        }
        return rootError("input -d $display swipe $sx1 $sy1 $sx2 $sy2 $duration")
    }

    private fun typeText(a: JsonObject): String? {
        val value = a.str("text") ?: return "缺少 text"
        val service = ZetaAccessibilityService.instance
        val node = a.int("node")?.let { index -> seen.getOrNull(index)?.node ?: return "没有编号 $index 的控件，先用 screen 重新看一下屏幕" }
            ?: service?.roots(display)?.firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        var done = false
        if (node != null) {
            val full = if (a.bool("append") == true) node.text?.toString().orEmpty() + value else value
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full) }
            done = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }.getOrDefault(false)
        }
        if (!done) {
            // 退路：系统的 input text 只认 ASCII
            if (node == null && service != null) return "没有找到输入框：先点一下要输入的地方，或者用 node 指明输入框"
            if (value.any { it.code > 126 || it.code < 32 }) return "这个输入框不接受直接填入，而备用的方式只能输入英文和数字"
            rootError("input -d $display text " + LinuxEnv.quote(value.replace(" ", "%s")))?.let { return it }
        }
        if (a.bool("submit") == true) {
            val entered = node != null && runCatching {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }.getOrDefault(false)
            if (!entered) return rootError("input -d $display keyevent 66")
        }
        return null
    }

    private fun pressKey(key: String): String? {
        val service = ZetaAccessibilityService.instance
        val name = key.lowercase()
        if (onBackground && (name == "home" || name == "recents")) {
            return "后台屏幕没有桌面和最近任务。换应用用 open_app，回上一页用 back"
        }
        // 全局动作只作用在主屏上；别的屏幕、或者没有无障碍服务时走按键事件
        if (service != null && !onBackground && display == 0) {
            val action = when (name) {
                "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> null
            }
            if (action != null && service.performGlobalAction(action)) return null
        }
        val command = when (name) {
            "back" -> "input -d $display keyevent 4"
            "home" -> "input -d $display keyevent 3"
            "recents" -> "input -d $display keyevent 187"
            "enter" -> "input -d $display keyevent 66"
            "delete" -> "input -d $display keyevent 67"
            "wake" -> "input keyevent 224"
            "notifications" -> "cmd statusbar expand-notifications"
            "quick_settings" -> "cmd statusbar expand-settings"
            else -> return "不认识的按键：$key"
        }
        return rootError(command)
    }

    private class App(val label: String, val packageName: String, val activity: String)

    private fun launchable(): List<App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = context.packageManager
        return manager.queryIntentActivities(intent, 0).map {
            App(it.loadLabel(manager).toString(), it.activityInfo.packageName, it.activityInfo.name)
        }
    }

    private fun match(query: String): List<App> {
        val q = query.trim()
        val apps = launchable()
        apps.filter { it.packageName == q || it.label.equals(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { return it }
        return apps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
    }

    private fun findApps(query: String): JsonObject {
        val apps = (if (query.isBlank()) launchable() else match(query)).sortedBy { it.label }
        if (apps.isEmpty()) return text("没有找到匹配“$query”的应用")
        return text(apps.take(200).joinToString("\n") { "${it.label}  ${it.packageName}" })
    }

    private fun openApp(a: JsonObject): JsonObject {
        val query = a.str("app").orEmpty()
        if (query.isBlank()) return failure("缺少 app（应用名或包名）")
        val found = match(query)
        val app = found.firstOrNull() ?: return failure("没有找到应用“$query”，可以用 find_apps 查一下")
        if (found.size > 1 && found.none { it.packageName == query || it.label.equals(query, ignoreCase = true) }) {
            return failure("有多个应用匹配“$query”：" + found.take(8).joinToString("、") { "${it.label}（${it.packageName}）" } + "。请用包名指明")
        }
        val requested = a.str("display")?.lowercase() ?: "auto"
        if (requested !in setOf("auto", "background", "main")) return failure("display 只能是 auto、background 或 main")
        val notes = ArrayList<String>()
        var screen: VirtualScreen? = null
        if (requested != "main") {
            val reason = when {
                !settings.backgroundScreen -> if (requested == "background") "用户在 Zeta 的设置里关掉了后台执行，在主屏上打开" else null
                app.packageName == context.packageName || app.packageName in MAIN_ONLY -> "${app.label} 只在主屏上打开"
                // 在后台屏幕上启动会把它从主屏抽走，打断用户正在做的事
                app.packageName == foregroundOnMain() -> "${app.label} 正开在主屏前台（用户可能正在用），就在主屏上操作，不把它挪走"
                else -> null
            }
            if (reason != null) notes += reason
            else screen = runCatching { ensureBackground() }.getOrElse {
                notes += "后台屏幕没能建起来（${it.message ?: it.javaClass.simpleName}），改在主屏上打开"
                null
            }
        }
        val target = screen?.displayId ?: mainDisplay
        if (screen != null) mute(app.packageName)
        rootError(
            "am start --user 0 --display $target -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n " + LinuxEnv.quote("${app.packageName}/${app.activity}") + " -f 0x10200000",
        )?.let { return failure(it) }
        onBackground = screen != null
        if (screen != null) {
            notes += "已在后台屏幕上打开 ${app.label}"
            main.post { if (background === screen) backgroundApp = app.label; onChanged() }
        }
        Thread.sleep(1500)
        return observe(screenshot = a.bool("screenshot") == true, maxNodes = 60, notes = notes)
    }

    /** 主屏前台的应用（用户眼前的那个）。 */
    private fun foregroundOnMain(): String? = rootTasks().firstOrNull { it.displayId == mainDisplay && it.visible }?.packageName

    private class RootTask(val id: Int, val displayId: Int, val packageName: String?, val visible: Boolean)

    /** 系统里的任务栈，最上层的在前（`am stack list`）。 */
    private fun rootTasks(): List<RootTask> {
        val out = root("am stack list").stdout
        val list = ArrayList<RootTask>()
        var id = -1
        var displayId = -1
        var packageName: String? = null
        var visible = false
        fun flush() {
            if (id >= 0) list += RootTask(id, displayId, packageName, visible)
        }
        for (line in out.lineSequence()) {
            val head = ROOT_TASK.find(line)
            if (head != null) {
                flush()
                id = head.groupValues[1].toInt()
                displayId = head.groupValues[2].toInt()
                packageName = null
                visible = false
                continue
            }
            val task = TASK.find(line) ?: continue
            if (packageName == null) packageName = task.groupValues[1]
            if (task.groupValues[2] == "true") visible = true
        }
        flush()
        return list
    }

    /** 这个对话的后台屏幕，没有就建一块（和主屏一样大）；在休眠就唤醒。失败时抛出异常。 */
    private fun ensureBackground(): VirtualScreen {
        screenRef?.takeIf { it.alive }?.let { existing ->
            wakeIfAsleep(existing)?.let { throw IllegalStateException(it) }
            return existing
        }
        check(!destroyed) { "这个会话已经结束" }
        beforeCreate(this)
        val metrics = DisplayMetrics()
        val physical = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("读不到主屏的尺寸")
        @Suppress("DEPRECATION") physical.getRealMetrics(metrics)
        val created = VirtualScreen.create(context, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        created.onGone = { onBackgroundGone(created) }
        screenRef = created
        main.post {
            background = created
            backgroundApp = null
            onChanged()
        }
        return created
    }

    /** 辅助进程意外退出：后台屏幕已经没了。 */
    private fun onBackgroundGone(screen: VirtualScreen) {
        if (screenRef !== screen) return
        screenRef = null
        restoreAll()
        main.post {
            if (background === screen) {
                background = null
                backgroundApp = null
            }
            onChanged()
            onScreenGone()
        }
    }

    private fun showToUser(a: JsonObject): JsonObject {
        val screen = screenRef?.takeIf { it.alive } ?: return failure(if (onBackground) lostBackground()!! else "现在没有后台屏幕，应用本来就开在主屏上")
        val label = backgroundApp
        val error = moveTopToMain(screen) ?: run {
            onBackground = false
            null
        }
        if (error != null) return failure(error)
        Thread.sleep(800)
        val reason = a.str("reason")?.takeIf { it.isNotBlank() }
        return observe(
            screenshot = a.bool("screenshot") == true,
            maxNodes = 60,
            notes = listOf("已把" + (label?.let { " $it" } ?: "后台屏幕上的应用") + " 移到主屏" + (reason?.let { "（$it）" } ?: "") + "，接下来在主屏上操作"),
        )
    }

    /** 把后台屏幕最上面的应用移到主屏；屏上没有别的应用了就把后台屏幕收掉。返回问题。 */
    private fun moveTopToMain(screen: VirtualScreen): String? {
        // 先唤醒：移过去的应用是醒着的
        if (screen.asleep.value && !screen.wake() && screen.alive) return "后台屏幕在休眠，没能唤醒"
        val task = rootTasks().firstOrNull { it.displayId == screen.displayId } ?: return "后台屏幕上没有应用"
        rootError("am display move-stack ${task.id} $mainDisplay")?.let { return it }
        task.packageName?.let { unmute(it) }
        if (rootTasks().none { it.displayId == screen.displayId }) releaseBackground()
        else main.post { onChanged() }
        return null
    }

    /** 界面上的“移到主屏”。 */
    fun moveToMainByUser() {
        thread(name = "zeta-phone-ui", isDaemon = true) {
            synchronized(lock) {
                val screen = screenRef?.takeIf { it.alive } ?: return@thread
                if (moveTopToMain(screen) == null) onBackground = false
            }
        }
    }

    /** 界面上的“关闭”。 */
    fun closeBackgroundByUser() {
        thread(name = "zeta-phone-ui", isDaemon = true) { synchronized(lock) { releaseBackground() } }
    }

    /** 释放后台屏幕（屏上的应用随之关闭）。当前屏幕若是它，保持指着它，好让模型下次调用时知道它没了。 */
    private fun releaseBackground() {
        val screen = screenRef ?: return
        screenRef = null
        screen.release()
        restoreAll()
        main.post {
            if (background === screen) {
                background = null
                backgroundApp = null
            }
            onChanged()
            onScreenGone()
        }
    }

    /** 全应用的后台屏幕满了，登记处挑中这块腾出来（和闲置释放一样，当前屏幕仍指着它，模型下次调用时会知道它没了）。 */
    internal fun evictBackground() {
        thread(name = "zeta-phone-evict", isDaemon = true) { synchronized(lock) { releaseBackground() } }
    }

    /** 有后台屏幕（醒着或睡着）。登记处据此决定留不留这份、屏幕数有没有到上限。 */
    internal val hasScreen: Boolean get() = screenRef?.alive == true

    /** 最近一次被 Claude Code 调用的时间，腾地方时先收最久没用的。 */
    internal val lastUsed: Long get() = lastUse

    /** 正在用它的对话是否在进行。 */
    internal val isBusy: Boolean get() = busy()

    /** 一轮结束：过一阵还没再用就让后台屏幕休眠，省电；应用和页面都留着，下次用时唤醒。 */
    fun onTurnEnded() {
        if (screenRef == null) return
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_SLEEP_MS)
    }

    private val idleCheck = Runnable {
        if (screenRef != null && !busy() && idle()) {
            thread(name = "zeta-phone-idle", isDaemon = true) {
                // 等锁的时候可能又用上了
                synchronized(lock) { if (idle()) screenRef?.sleep() }
            }
        }
    }

    private fun idle() = System.currentTimeMillis() - lastUse >= IDLE_SLEEP_MS - 1000

    /** 让这个对话的后台屏幕休眠（有的话）：不销毁，屏上的应用和页面都留着，下次用工具时唤醒。可以在任何线程上调用。 */
    internal fun sleepBackground() {
        thread(name = "zeta-phone-sleep", isDaemon = true) { synchronized(lock) { screenRef?.sleep() } }
    }

    /** 换到现在用它的对话（重新打开会话时会新建对话对象）：闲置判断、界面刷新都跟着它走。在主线程上调用。 */
    fun attach(busy: () -> Boolean, onChanged: () -> Unit) {
        this.busy = busy
        this.onChanged = onChanged
    }

    /**
     * 对话的进程结束了：后台屏幕和上面的应用留着，只让它睡下，用 --resume 接着的 Claude 还在原来那块屏上。
     * 控件编号跟着上一个进程作废。马上就睡了，不用再等闲置检查。
     */
    fun detach() {
        main.removeCallbacks(idleCheck)
        thread(name = "zeta-phone-detach", isDaemon = true) {
            synchronized(lock) {
                if (destroyed) return@thread
                // 已经持着锁，直接睡下（sleepBackground 会另起线程再来拿锁）
                screenRef?.sleep()
                seen = emptyList()
            }
        }
    }

    /** 彻底释放（会话删了、换了环境、或已经没人用）：收掉后台屏幕，之后也不再建。 */
    fun destroy() {
        destroyed = true
        main.removeCallbacks(idleCheck)
        thread(name = "zeta-phone-stop", isDaemon = true) {
            synchronized(lock) {
                releaseBackground()
                onBackground = false
                seen = emptyList()
            }
        }
    }

    /** 在后台屏幕上运行的应用先禁掉音频焦点：不会突然出声，也不打断用户在听的东西。 */
    private fun mute(packageName: String) {
        synchronized(muted) { if (packageName in muted) return }
        val out = root("cmd appops get --user 0 ${LinuxEnv.quote(packageName)} TAKE_AUDIO_FOCUS").stdout
        val mode = Regex("TAKE_AUDIO_FOCUS: (\\w+)").find(out)?.groupValues?.get(1) ?: "default"
        if (mode == "ignore") return
        if (root("cmd appops set --user 0 ${LinuxEnv.quote(packageName)} TAKE_AUDIO_FOCUS ignore").exitCode != 0) return
        synchronized(muted) { muted[packageName] = mode }
        rememberMuted(settings, packageName, mode)
    }

    private fun unmute(packageName: String) {
        val mode = synchronized(muted) { muted.remove(packageName) } ?: return
        root("cmd appops set --user 0 ${LinuxEnv.quote(packageName)} TAKE_AUDIO_FOCUS $mode")
        forgetMuted(settings, packageName)
    }

    private fun restoreAll() {
        synchronized(muted) { muted.keys.toList() }.forEach(::unmute)
    }

    // ---- Android 侧命令 ----

    private fun shell(a: JsonObject): JsonObject {
        val command = a.str("command")?.takeIf { it.isNotBlank() } ?: return failure("缺少 command")
        val timeout = (a.int("timeout_seconds") ?: 30).coerceIn(1, 600)
        val result = root(command, timeout * 1000L)
        val body = buildString {
            if (result.stdout.isNotEmpty()) append(tail(result.stdout))
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append("[stderr]\n").append(tail(result.stderr))
            }
            if (isNotEmpty() && !endsWith('\n')) append('\n')
            append(if (result.exitCode == -1 && result.stderr == "timeout") "[超过 $timeout 秒，已中止]" else "[退出码 ${result.exitCode}]")
        }
        return if (result.exitCode == 0) text(body) else failure(body)
    }

    private fun tail(value: String): String = if (value.length <= OUTPUT_LIMIT) value else
        "…（前面省略 ${value.length - OUTPUT_LIMIT} 个字符）\n" + value.takeLast(OUTPUT_LIMIT)

    /** 在 Android 侧（全局挂载命名空间）以 root 执行。 */
    private fun root(command: String, timeoutMs: Long = 15_000) =
        LinuxEnv.runRoot(LinuxEnv.androidScript("exec /system/bin/sh -c \"\$2\"", command), timeoutMs)

    private fun rootError(command: String): String? {
        val result = root(command)
        return if (result.exitCode == 0) null else "执行失败：" + result.stderr.ifBlank { result.stdout }.trim().take(300).ifBlank { "退出码 ${result.exitCode}" }
    }

    companion object {
        const val SERVER = "phone"
        private const val MAX_EDGE = 1568
        private const val IDLE_SLEEP_MS = 10 * 60_000L

        /** 只在主屏上打开的：桌面、系统界面。 */
        private val MAIN_ONLY = setOf(
            "android", "com.android.systemui", "com.miui.home", "com.android.launcher", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.oppo.launcher", "com.bbk.launcher2", "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
        )

        private val ROOT_TASK = Regex("^RootTask id=(\\d+) .*displayId=(-?\\d+)")
        private val TASK = Regex("^\\s+taskId=\\d+: ([^/\\s]+)/\\S* .*visible=(true|false)")

        private val mutedLock = Any()

        private fun rememberMuted(settings: Settings, packageName: String, mode: String) = synchronized(mutedLock) {
            settings.mutedApps = settings.mutedApps.filterNot { it.substringBefore('=') == packageName }.toSet() + "$packageName=$mode"
        }

        private fun forgetMuted(settings: Settings, packageName: String) = synchronized(mutedLock) {
            settings.mutedApps = settings.mutedApps.filterNot { it.substringBefore('=') == packageName }.toSet()
        }

        /**
         * 上次 Zeta 没来得及恢复的音频焦点设置（比如被系统杀掉）：后台屏幕随 Zeta 一起没了，这里把设置改回去。
         * 应用启动时调用一次。
         */
        fun restoreMutedApps(settings: Settings) {
            val left = synchronized(mutedLock) { settings.mutedApps }
            if (left.isEmpty()) return
            for (entry in left) {
                val packageName = entry.substringBefore('=')
                val mode = entry.substringAfter('=', "default")
                runCatching {
                    LinuxEnv.runRoot(
                        LinuxEnv.androidScript("exec cmd appops set --user 0 \"\$2\" TAKE_AUDIO_FOCUS \"\$3\"", packageName, mode), 10_000,
                    )
                }
            }
            synchronized(mutedLock) { settings.mutedApps = settings.mutedApps - left }
        }
        private const val OUTPUT_LIMIT = 30_000
        const val SERVICE = "com.dzshzx.zeta/com.dzshzx.zeta.phone.ZetaAccessibilityService"

        /** 传给 `claude --mcp-config` 的声明。 */
        val MCP_CONFIG: String = buildJsonObject {
            putJsonObject("mcpServers") { putJsonObject(SERVER) { put("type", "sdk"); put("name", SERVER) } }
        }.toString()

        /** 无障碍服务是否连着。 */
        val accessibilityConnected: Boolean get() = ZetaAccessibilityService.instance != null

        /** 用 root 把 Zeta 的无障碍服务加进系统的启用列表，等它连上。返回是否连上了。 */
        fun enableAccessibility(context: Context): Boolean {
            if (accessibilityConnected) return true
            val script = "s=$SERVICE; cur=\$(settings get secure enabled_accessibility_services); " +
                "case \":\$cur:\" in *\":\$s:\"*) ;; *) " +
                "if [ -z \"\$cur\" ] || [ \"\$cur\" = null ]; then new=\$s; else new=\"\$cur:\$s\"; fi; " +
                "settings put secure enabled_accessibility_services \"\$new\" ;; esac; settings put secure accessibility_enabled 1"
            runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script), 10_000) }
            repeat(25) {
                if (accessibilityConnected) return true
                Thread.sleep(200)
            }
            return false
        }

        /** 用 root 把 Zeta 的无障碍服务从启用列表里拿掉。 */
        fun disableAccessibility() {
            val script = "s=$SERVICE; cur=\$(settings get secure enabled_accessibility_services); new=; IFS=:; " +
                "for i in \$cur; do [ \"\$i\" = \"\$s\" ] || [ \"\$i\" = null ] || new=\"\${new:+\$new:}\$i\"; done; unset IFS; " +
                "if [ -n \"\$new\" ]; then settings put secure enabled_accessibility_services \"\$new\"; " +
                "else settings delete secure enabled_accessibility_services; fi"
            runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script), 10_000) }
        }

        private const val INSTRUCTIONS =
            "These tools operate the Android phone this conversation runs on. " +
                "Use `screen` to read what is on the display: it lists the visible controls, each with an index and its centre. " +
                "Prefer acting on a control by its index (`node`), which stays valid until the next observation; use x/y only when the control is not listed. " +
                "All coordinates are in screenshot pixels, the size reported at the top of every observation. " +
                "Every action returns a fresh list of controls, so you rarely need to call `screen` again after acting. " +
                "Apps you open with `open_app` run on a hidden background screen by default, so the user can keep using their phone meanwhile " +
                "(they can watch it live in Zeta). Every tool acts on the current screen, which is the one you last opened an app on; " +
                "each observation starts by naming it. The background screen has no home screen or status bar: switch apps with `open_app`, go back with `press_key` back. " +
                "Call `show_to_user` to move the app from the background screen to the main screen when the user must act themselves " +
                "(payment, password, captcha, a login or permission prompt meant for them) or when they want to see the result in that app; after it you are on the main screen. " +
                "Use `display: main` with `open_app` only when the user wants it done in front of them. " +
                "Ask for `screenshot: true` only when the list is not enough (images, games, canvas-drawn pages); it costs far more than the list. " +
                "`shell` runs a command as root on the Android side, outside the Linux environment your Bash tool lives in: " +
                "use it for pm, am, cmd, settings, dumpsys, getprop, logcat, input and the like. Opening a URL: am start -a android.intent.action.VIEW -d <url> " +
                "(add --display <id> with the background screen's display id, shown in observations, to open it there). " +
                "If something needs the user's own hands, bring it to them with `show_to_user` and tell them what to do."

        private fun JsonObjectBuilder.schema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit) {
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties", properties)
                if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }

        private fun JsonObjectBuilder.prop(name: String, type: String, description: String) {
            putJsonObject(name) { put("type", type); put("description", description) }
        }

        private fun JsonObjectBuilder.enumProp(name: String, values: List<String>, description: String) {
            putJsonObject(name) {
                put("type", "string")
                put("enum", JsonArray(values.map { JsonPrimitive(it) }))
                put("description", description)
            }
        }

        private fun JsonObjectBuilder.after() =
            prop("screenshot", "boolean", "Also return a screenshot taken after the action. Default false.")

        private fun tool(name: String, description: String, readOnly: Boolean = false, build: JsonObjectBuilder.() -> Unit): JsonElement = buildJsonObject {
            put("name", name)
            put("description", description)
            build()
            if (readOnly) putJsonObject("annotations") { put("readOnlyHint", true) }
        }

        private val TOOLS: JsonArray = buildJsonArray {
            add(tool("screen", "Read the current screen: the foreground app and a numbered list of visible controls (text, kind, centre in screenshot pixels, what can be done with each). Optionally a screenshot.", readOnly = true) {
                schema {
                    enumProp("display", listOf("main", "background"), "Switch to this screen first: main (what the user sees) or background (this conversation's hidden screen). Omit to stay on the current one.")
                    prop("screenshot", "boolean", "Also return a screenshot. Default false; use it when the list of controls is not enough.")
                    prop("max_nodes", "integer", "How many controls to list at most (10-250, default 80).")
                }
            })
            add(tool("tap", "Tap a control (by its index from the latest observation) or a point on the screen.") {
                schema {
                    prop("node", "integer", "Index of the control to tap.")
                    prop("x", "integer", "X in screenshot pixels, when not using node.")
                    prop("y", "integer", "Y in screenshot pixels, when not using node.")
                    after()
                }
            })
            add(tool("long_press", "Press and hold a control or a point.") {
                schema {
                    prop("node", "integer", "Index of the control.")
                    prop("x", "integer", "X in screenshot pixels, when not using node.")
                    prop("y", "integer", "Y in screenshot pixels, when not using node.")
                    prop("duration_ms", "integer", "How long to hold (300-5000, default 700).")
                    after()
                }
            })
            add(tool("swipe", "Swipe from one point to another, e.g. to scroll: swiping from lower to upper reveals content further down.") {
                schema(required = listOf("x1", "y1", "x2", "y2")) {
                    prop("x1", "integer", "Start X in screenshot pixels.")
                    prop("y1", "integer", "Start Y in screenshot pixels.")
                    prop("x2", "integer", "End X in screenshot pixels.")
                    prop("y2", "integer", "End Y in screenshot pixels.")
                    prop("duration_ms", "integer", "Duration (50-5000, default 300). Slower swipes scroll more precisely.")
                    after()
                }
            })
            add(tool("type_text", "Put text into an input field: the one given by node, or else the field that currently has focus. Replaces what is there unless append is true.") {
                schema(required = listOf("text")) {
                    prop("text", "string", "The text to enter.")
                    prop("node", "integer", "Index of the input field. Omit to use the focused field.")
                    prop("append", "boolean", "Add to the existing text instead of replacing it. Default false.")
                    prop("submit", "boolean", "Press Enter / the keyboard's action button afterwards. Default false.")
                    after()
                }
            })
            add(tool("press_key", "Press a system key.") {
                schema(required = listOf("key")) {
                    putJsonObject("key") {
                        put("type", "string")
                        put("enum", JsonArray(listOf("back", "home", "recents", "enter", "delete", "wake", "notifications", "quick_settings").map { JsonPrimitive(it) }))
                        put("description", "back, home, recents (app switcher), enter, delete (backspace), wake (turn the screen on), notifications (open the shade), quick_settings. " +
                            "home and recents do not exist on the background screen; wake, notifications and quick_settings always act on the main screen.")
                    }
                    after()
                }
            })
            add(tool("open_app", "Launch an app by its name or package name and bring it to the front, by default on the hidden background screen. Later tools act on the screen it opened on.") {
                schema(required = listOf("app")) {
                    prop("app", "string", "App name as shown on the home screen (e.g. 微信) or package name (e.g. com.tencent.mm).")
                    enumProp(
                        "display", listOf("auto", "background", "main"),
                        "auto (default): the background screen, except the home screen, system UI and apps already in front of the user, which stay on the main screen. " +
                            "background: insist on the background screen. main: open it in front of the user.",
                    )
                    after()
                }
            })
            add(tool("show_to_user", "Move the app on the background screen to the main screen, in front of the user, for steps they must do themselves (payment, password, captcha, a login or permission prompt) or to show them the result. Afterwards tools act on the main screen.") {
                schema {
                    prop("reason", "string", "Short reason, e.g. 需要你输入支付密码.")
                    after()
                }
            })
            add(tool("find_apps", "List installed apps that can be launched, with their package names. Empty query lists all.", readOnly = true) {
                schema { prop("query", "string", "Part of the app name or package name.") }
            })
            add(tool("shell", "Run a shell command as root on the Android side (not inside the Linux environment): pm, am, cmd, settings, dumpsys, getprop, logcat, input, screencap, etc. Returns stdout, stderr and the exit code.") {
                schema(required = listOf("command")) {
                    prop("command", "string", "The command line, run by /system/bin/sh.")
                    prop("timeout_seconds", "integer", "Give up after this long (1-600, default 30).")
                }
            })
        }
    }
}
