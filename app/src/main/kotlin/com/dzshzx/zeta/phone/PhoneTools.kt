package com.dzshzx.zeta.phone

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
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
 */
class PhoneTools(private val context: Context, private val settings: Settings) {
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

    private val display: Int get() = settings.phoneDisplay

    // ---- MCP ----

    /** 处理一条 MCP 消息，返回要送回去的应答（通知类消息没有应答，返回空对象）。 */
    fun handle(message: JsonObject): JsonObject {
        val id = message["id"] ?: return JsonObject(emptyMap())
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
            when (name) {
                "screen" -> observe(screenshot = a.bool("screenshot") == true, maxNodes = (a.int("max_nodes") ?: 80).coerceIn(10, 250))
                "tap" -> act(a) { screen -> tap(screen, a, longPressMs = null) }
                "long_press" -> act(a) { screen -> tap(screen, a, longPressMs = (a.int("duration_ms") ?: 700).coerceIn(300, 5000).toLong()) }
                "swipe" -> act(a) { screen -> swipe(screen, a) }
                "type_text" -> act(a) { typeText(a) }
                "press_key" -> act(a) { pressKey(a.str("key").orEmpty()) }
                "open_app" -> act(a, settleMs = 1500) { openApp(a.str("app").orEmpty()) }
                else -> failure("没有这个工具：$name")
            }
        }
    }

    // ---- 读屏 ----

    private fun screen(): Screen? {
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
        val notes = buildList {
            if (display == 0 && power?.isInteractive == false) add("屏幕是关着的（可以用 shell 执行 input keyevent WAKEUP）")
            if (display == 0 && guard?.isKeyguardLocked == true) add("手机处于锁屏状态")
        }
        return "屏幕 ${screen.modelWidth}×${screen.modelHeight}（截图坐标）" + notes.joinToString("") { "；$it" }
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

    private fun observe(screenshot: Boolean, maxNodes: Int): JsonObject {
        val screen = screen() ?: return failure("找不到编号为 $display 的屏幕")
        val tree = describe(screen, maxNodes)
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
        val raw: Bitmap = ZetaAccessibilityService.instance?.screenshot(display) ?: run {
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
        // 全局动作只作用在主屏上；别的屏幕、或者没有无障碍服务时走按键事件
        if (service != null && display == 0) {
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

    private fun openApp(query: String): String? {
        if (query.isBlank()) return "缺少 app（应用名或包名）"
        val found = match(query)
        val app = found.firstOrNull() ?: return "没有找到应用“$query”，可以用 find_apps 查一下"
        if (found.size > 1 && found.none { it.packageName == query || it.label.equals(query, ignoreCase = true) }) {
            return "有多个应用匹配“$query”：" + found.take(8).joinToString("、") { "${it.label}（${it.packageName}）" } + "。请用包名指明"
        }
        return rootError(
            "am start --user 0 --display $display -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n " + LinuxEnv.quote("${app.packageName}/${app.activity}") + " -f 0x10200000",
        )
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
                "Ask for `screenshot: true` only when the list is not enough (images, games, canvas-drawn pages); it costs far more than the list. " +
                "`shell` runs a command as root on the Android side, outside the Linux environment your Bash tool lives in: " +
                "use it for pm, am, cmd, settings, dumpsys, getprop, logcat, input and the like. Opening a URL: am start -a android.intent.action.VIEW -d <url>. " +
                "The user sees their phone being operated; if something needs their own hands (unlocking, a payment, a captcha), stop and tell them."

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

        private fun JsonObjectBuilder.after() =
            prop("screenshot", "boolean", "Also return a screenshot taken after the action. Default false.")

        private fun tool(name: String, description: String, readOnly: Boolean = false, build: JsonObjectBuilder.() -> Unit): JsonElement = buildJsonObject {
            put("name", name)
            put("description", description)
            build()
            if (readOnly) putJsonObject("annotations") { put("readOnlyHint", true) }
        }

        private val TOOLS: JsonArray = buildJsonArray {
            add(tool("screen", "Read the phone's screen: the foreground app and a numbered list of visible controls (text, kind, centre in screenshot pixels, what can be done with each). Optionally a screenshot.", readOnly = true) {
                schema {
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
                        put("description", "back, home, recents (app switcher), enter, delete (backspace), wake (turn the screen on), notifications (open the shade), quick_settings.")
                    }
                    after()
                }
            })
            add(tool("open_app", "Launch an app by its name or package name and bring it to the front.") {
                schema(required = listOf("app")) {
                    prop("app", "string", "App name as shown on the home screen (e.g. 微信) or package name (e.g. com.tencent.mm).")
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
