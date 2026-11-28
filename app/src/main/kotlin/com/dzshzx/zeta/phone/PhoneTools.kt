package com.dzshzx.zeta.phone

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.PhoneSession
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.Unattended
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.core.diag.DiagEvents
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.schedule.UnattendedRun
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.phone.tools.CallContext
import com.dzshzx.zeta.phone.tools.PhoneToolGroups
import com.dzshzx.zeta.phone.tools.rootShell
import com.dzshzx.zeta.phone.tools.toolFailure
import com.dzshzx.zeta.phone.tools.toolText
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 交给 Claude Code 的“手机”工具：读屏、点按、输入、开应用、在 Android 侧以 root 执行命令。
 *
 * 它是一个 MCP 服务器，但不开端口：Claude Code 把 MCP 消息夹在本来就有的控制通道里发过来 （`--mcp-config` 里声明
 * `{"type":"sdk"}`），[handle] 处理完再原路送回去。
 *
 * 坐标一律用“截图坐标”：屏幕按比例缩到长边不超过 [MAX_EDGE] 之后的像素。 这样模型看到的截图、控件列表里的位置、它填的点击位置是同一套数，不用换算。
 *
 * 每个会话一份（由 ConversationHub 按会话登记）：应用默认开在这个会话自己的后台屏幕（[VirtualScreen]）上，不占用主屏，
 * 几个对话同时操作手机也互不干扰。工具作用在“当前屏幕”上：最近一次打开应用的那块。 后台屏幕的建、睡、醒、释放在
 * [AgentDisplaySlot]：对话的进程结束时屏留着、睡下，重新打开会话、接着发送时还是那块屏和上面的应用。 屏幕这一套工具的定义和说法在
 * [ScreenToolDefs]，不碰屏幕的工具组在 `phone/tools`。
 *
 * 三种同步各管一样：
 * - 屏幕锁 [lock]：屏幕动作、用户在后台屏幕上的手势、后台屏幕的建睡醒放互斥（[AgentDisplaySlot] 用同一把）。 wait 等着时用 `lock.wait`
 *   让出它，用户的手势才插得进来。
 * - 闸门 [ControlGate]：暂停、接管时工具线程在它前面等。它用自己的锁，等着的时候不持屏幕锁，用户的手势照样能拿。
 * - 主线程投递：界面要看的状态（mutableStateOf：胶囊、边缘光、后台屏幕和暂停接管的状态）只在主线程上改， 工具线程经 [onMainAsync] 或 `main.post`
 *   投递过去。
 */
class PhoneTools(
    private val context: Context,
    private val settings: Settings,
    /** 用这份手机工具的对话在哪台主机上（[Backends]）；随每次调用交给工具组。 */
    private val backend: String,
    /** 要新建后台屏幕之前调用（工具线程上，持着本对象的屏幕锁）：全应用的后台屏幕有上限，满了由登记处先腾出一块。 */
    beforeCreate: (PhoneTools) -> Unit = {},
    /** 后台屏幕没了（在主线程上调用）：登记处据此丢掉没有屏、也没有对话在用的那些。 */
    onScreenGone: () -> Unit = {},
    /** 用这份手机工具的对话是否正在进行（后台任务在跑也算）：后台屏幕不休眠、不被腾掉。任何线程上都可能调用。 */
    isBusy: (PhoneTools) -> Boolean = { false },
    /** 后台屏幕有了、没了或换了应用（在主线程上调用）。 */
    onChanged: () -> Unit = {},
) : PhoneSession {
    override val mcpConfig: String
        get() = MCP_CONFIG

    /** 这个对话正在跑的无人值守后台运行（定时任务、通知触发），随 Conversation.unattended 一起挂上、摘掉。随每次调用交给工具组（[CallContext]）。 */
    @Volatile override var unattended: Unattended? = null

    /** 当前对话使用的命令行工具（[com.dzshzx.zeta.core.Harnesses]）；随每次调用交给工具组（[CallContext]）。 */
    @Volatile override var harness: String = com.dzshzx.zeta.core.Harnesses.CLAUDE

    private class Screen(val width: Int, val height: Int) {
        val scale: Float = minOf(1f, MAX_EDGE / max(width, height).toFloat())

        fun toModel(v: Int): Int = (v * scale).roundToInt()

        fun toScreen(v: Int): Int = (v / scale).roundToInt()

        val modelWidth
            get() = toModel(width)

        val modelHeight
            get() = toModel(height)
    }

    private class Seen(val node: AccessibilityNodeInfo, val bounds: Rect)

    /**
     * 按编号操作用的控件列表：Claude 这条回复看到的那一份。 动作之后交回去的新列表，Claude 要到下一条回复才看得到，先放在
     * [latest]。同一条回复里一起发出的几个动作都照这一份编号， 不然前一个动作改了界面、编号挪了位，后一个就点错（按 C 之后再点“1”，点到的是“2”）。
     */
    @Volatile private var seen: List<Seen> = emptyList()
    @Volatile private var latest: List<Seen> = emptyList()
    @Volatile private var replyId: String? = null

    /** 屏幕锁：屏幕动作、用户的手势、后台屏幕的建睡醒放互斥（见类的说明）。 */
    private val lock = Any()

    /** 暂停、接管的闸门。 */
    private val gate = ControlGate()

    /** 这个对话的后台屏幕。 */
    internal val slot =
        AgentDisplaySlot(
            context,
            lock,
            gate,
            beforeCreate = { beforeCreate(this) },
            onScreenGone = onScreenGone,
            busy = { isBusy(this) },
            onChanged = onChanged,
        )

    /** 主屏的编号。平时是 0；调试时可以经设置指到别的屏上，免得测试打扰正在用手机的人。 */
    private val mainDisplay: Int
        get() = settings.phoneDisplay

    private val main = Handler(Looper.getMainLooper())

    /** 这个对话的后台屏幕（界面用，只在主线程上改）。 */
    val agentDisplay: VirtualScreen?
        get() = slot.agentDisplay

    /** 后台屏幕上最近打开的应用名（界面用）。 */
    override val agentDisplayApp: String?
        get() = slot.agentDisplayApp

    /** 工具线程看到的后台屏幕。 */
    private val screenRef: VirtualScreen?
        get() = slot.current

    /** screen_record 的录屏，同一时间最多一段。 */
    private val recorder = ScreenRecorder(context, settings)

    /** 当前操作的是后台屏幕。后台屏幕没了也保持为 true，直到模型换屏，免得它不知情地点到主屏上。 */
    @Volatile private var onAgentDisplay = false

    private val display: Int
        get() = if (onAgentDisplay) screenRef?.displayId ?: Display.INVALID_DISPLAY else mainDisplay

    /** 当前屏幕是后台屏幕但它已经没了：返回给模型的说明。 */
    private fun lostAgentDisplay(): String? =
        if (onAgentDisplay && screenRef?.alive != true) {
            "后台屏幕已经关掉了（用户在 Zeta 里关的，或者同时开着的后台屏幕太多、这块被腾掉了）。要继续就用 open_app 重新打开应用；要看主屏用 screen 并传 display: main。"
        } else null

    /** 要操作当前屏幕之前：后台屏幕没了返回说明；在休眠就先唤醒（等到新画面出来）。 */
    private fun ready(): String? {
        lostAgentDisplay()?.let {
            return it
        }
        if (!onAgentDisplay) return null
        val screen = screenRef ?: return null
        return slot.wakeIfAsleep(screen, ::lostAgentDisplay)
    }

    // ---- 悬浮层（顶部胶囊、主屏边缘光）用的状态 ----

    /** 一次正在进行的手机工具调用，用人话说是在做什么（“点按「搜索」”）。[toolUseId] 对上聊天里正在跑的那一步时胶囊用它。 */
    data class CallInfo(val toolUseId: String?, val tool: String, val text: String)

    /** 这一轮看过屏或动过手（screen、tap、open_app 这些，不含 shell、find_apps）。胶囊据此挑对话。一轮结束时清掉。 */
    var engagedThisTurn by mutableStateOf(false)
        private set

    /** 最近一次看屏或动手的时间；几个对话都在用手机时，胶囊显示最近的那个。 */
    var engagedAt by mutableLongStateOf(0L)
        private set

    /** 这一轮最近一次动手是在主屏上（主屏边缘光据此显示）。 */
    var actingOnMain by mutableStateOf(false)
        private set

    /** 最近一次手机工具调用（这一轮内）。 */
    var currentCall by mutableStateOf<CallInfo?>(null)
        private set

    /** 用户点了暂停：之后的手机工具调用先等着。 */
    val paused: Boolean
        get() = gate.paused

    /** 用户在胶囊的展开画面上接管了后台屏幕：Claude 的手机工具调用先等着，直到交还。 */
    val userControlling: Boolean
        get() = gate.userControlling

    private val touchFlow = MutableSharedFlow<TouchMark>(extraBufferCapacity = 16)

    /** Claude 和用户的每次点按、长按、滑动，坐标按所在屏幕归一化。 */
    val touches: SharedFlow<TouchMark> = touchFlow

    /** 用户在后台屏幕上动过手：下一次看屏 / 动手时旧的控件编号作废，并告诉 Claude。 */
    private val touchedByUser = AtomicBoolean(false)

    /** 正在处理的工具调用数（在闸门前等用户放行的也算）。 */
    private val callsInFlight = AtomicInteger(0)

    /** 有工具调用正在处理：这时结束本应用，这次调用会失败。任何线程上都可能读。 */
    val inCall: Boolean
        get() = callsInFlight.get() > 0

    /** 用户做了 Claude 不知道的事（比如把应用移到了主屏），下一次看屏 / 动手时附在结果前面。 */
    private val userNotes = ConcurrentLinkedQueue<String>()

    /** 在主线程上执行：已经在主线程就当场做，否则投递过去、不等它做完（和等结果的 core 的 onMain 不同）。 */
    private fun onMainAsync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 胶囊上的“暂停”：Claude 下一次手机操作起先等着。 */
    fun pauseByUser() = gate.set(pause = true, control = false)

    /** 胶囊上的接管：用户在展开画面上自己操作后台屏幕，Claude 的手机操作先等着。没有后台屏幕时不接管。 */
    fun takeOverByUser() {
        if (agentDisplay == null) return
        gate.set(pause = false, control = true)
    }

    /** 继续 / 交还给 Claude。 */
    fun resumeByUser() = gate.set(pause = false, control = false)

    /** 停止这一轮（或别的原因 [reason]）：闸门前等着的调用作废，暂停和接管都解除。等着用户交还浏览器的调用也一并作废。 */
    override fun cancelWaiting() = cancelWaiting(STOPPED)

    /** 等着的调用不再等，交给模型的原因是 [reason]。 */
    fun cancelWaiting(reason: String) {
        gate.set(pause = false, control = false, cancel = reason)
        com.dzshzx.zeta.phone.browser.AgentBrowser.cancelWaiting()
    }

    /** 用户在展开画面上点按（坐标按画面归一化），作用在后台屏幕上；没在接管就先接管。在主线程上调用。 */
    fun userTap(x: Float, y: Float, longPress: Boolean) {
        val screen = agentDisplay ?: return
        if (!userControlling) takeOverByUser()
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        touchFlow.tryEmit(
            TouchMark(onAgentDisplay = true, x = nx, y = ny, longPress = longPress, byUser = true),
        )
        touchedByUser.set(true)
        val px = (nx * (screen.width - 1)).roundToInt()
        val py = (ny * (screen.height - 1)).roundToInt()
        val duration = if (longPress) USER_LONG_PRESS_MS else USER_TAP_MS
        userGestures.execute {
            dispatchByUser(
                screen,
                Path().apply { moveTo(px.toFloat(), py.toFloat()) },
                duration,
                if (longPress) "input -d ${screen.displayId} swipe $px $py $px $py $duration"
                else "input -d ${screen.displayId} tap $px $py",
            )
        }
    }

    /** 用户在展开画面上滑动。在主线程上调用。 */
    fun userSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Int) {
        val screen = agentDisplay ?: return
        if (!userControlling) takeOverByUser()
        val sx = startX.coerceIn(0f, 1f)
        val sy = startY.coerceIn(0f, 1f)
        val ex = endX.coerceIn(0f, 1f)
        val ey = endY.coerceIn(0f, 1f)
        touchFlow.tryEmit(
            TouchMark(
                onAgentDisplay = true,
                x = sx,
                y = sy,
                endX = ex,
                endY = ey,
                swipe = true,
                byUser = true,
            ),
        )
        touchedByUser.set(true)
        val x1 = (sx * (screen.width - 1)).roundToInt()
        val y1 = (sy * (screen.height - 1)).roundToInt()
        val x2 = (ex * (screen.width - 1)).roundToInt()
        val y2 = (ey * (screen.height - 1)).roundToInt()
        val duration = durationMs.coerceIn(100, 2000).toLong()
        userGestures.execute {
            dispatchByUser(
                screen,
                Path().apply {
                    moveTo(x1.toFloat(), y1.toFloat())
                    lineTo(x2.toFloat(), y2.toFloat())
                },
                duration,
                "input -d ${screen.displayId} swipe $x1 $y1 $x2 $y2 $duration",
            )
        }
    }

    /** 用户的手势（[userGestures] 的线程上，按先后一个个做）：拿着 [lock]，不和 Claude 正在做的动作交错。 */
    private fun dispatchByUser(
        screen: VirtualScreen,
        path: Path,
        durationMs: Long,
        fallback: String,
    ) {
        synchronized(lock) {
            if (screenRef !== screen || !screen.alive) return
            if (screen.asleep.value && !screen.wake()) return
            if (
                ZetaAccessibilityService.instance?.gesture(screen.displayId, path, durationMs) ==
                    true
            )
                return
            root(fallback)
        }
    }

    /** 胶囊上的“全屏”：把后台屏幕上的应用移到主屏。用户正在接管的话改为暂停，免得 Claude 接着在主屏上和用户抢。 */
    fun handoffToMainByUser() {
        if (userControlling) gate.set(pause = true, control = false)
        moveToMainByUser()
    }

    /** 一轮结束、进程结束或销毁：清掉这一轮的悬浮层状态，等着的调用作废。 */
    private fun resetOverlay() = onMainAsync {
        engagedThisTurn = false
        actingOnMain = false
        currentCall = null
        gate.set(pause = false, control = false, cancel = STOPPED)
    }

    /** 这次调用看屏或动手了（工具线程上）。 */
    private fun engage() {
        val now = System.currentTimeMillis()
        main.post {
            engagedThisTurn = true
            engagedAt = now
        }
    }

    /** Claude 开始了一条新回复（消息 id 变了）：上一批工具结果它已经看到了，从这里起按其中最新的控件列表编号。 */
    override fun onReply(messageId: String) {
        if (messageId == replyId) return
        replyId = messageId
        seen = latest
    }

    private fun forget() {
        seen = emptyList()
        latest = emptyList()
    }

    /** 编号对应的控件。没收到过回复的通知（比如子代理里的调用）就退回最新的一份。 */
    private fun listed(index: Int): Seen? = seen.ifEmpty { latest }.getOrNull(index)

    /** 胶囊上的一句，给还没轮到执行的调用（几个一起发出、排着队的时候）。 */
    fun describeCall(tool: String, input: JsonObject?): String =
        callText(tool, input ?: JsonObject(emptyMap()))

    /** 胶囊才知道的现场：控件编号按这次调用要用的那批对上控件，说得出点的是哪个。 */
    private fun scene() =
        CallScene(onAgentDisplay, agentDisplayApp) { index ->
            listed(index)?.node?.let { SceneControl(nodeLabel(it), it.isPassword) }
        }

    /** 调用开始（工具线程上，持着 [lock]，控件编号还是这次调用要用的那批）：告诉胶囊在做什么、在哪块屏上。 */
    private fun reportCall(toolUseId: String?, name: String, a: JsonObject) {
        val call = CallInfo(toolUseId, name, callText(name, a))
        val onMainScreen =
            when (name) {
                "tap",
                "long_press",
                "swipe",
                "scroll",
                "type_text" -> !onAgentDisplay
                "press_key" -> !onAgentDisplay || a.str("key")?.lowercase() in GLOBAL_KEYS
                else -> null
            }
        main.post {
            currentCall = call
            if (onMainScreen != null) actingOnMain = onMainScreen
        }
    }

    /** 胶囊上的一句：“点按「搜索」”“在后台操作计算器”“看屏幕”。 */
    private fun callText(name: String, a: JsonObject): String =
        PhoneToolCatalog.title(name, a, scene()) ?: name

    private fun nodeLabel(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        val raw =
            node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: return null
        return raw.clipLine(12)
    }

    /** Claude 的一次触摸（[Screen] 的像素），归一化后交给悬浮层画光效。 */
    private fun mark(
        screen: Screen,
        x: Int,
        y: Int,
        endX: Int = x,
        endY: Int = y,
        swipe: Boolean = false,
        longPress: Boolean = false,
    ) {
        val w = screen.width.toFloat().coerceAtLeast(1f)
        val h = screen.height.toFloat().coerceAtLeast(1f)
        touchFlow.tryEmit(
            TouchMark(
                onAgentDisplay = onAgentDisplay,
                x = (x / w).coerceIn(0f, 1f),
                y = (y / h).coerceIn(0f, 1f),
                endX = (endX / w).coerceIn(0f, 1f),
                endY = (endY / h).coerceIn(0f, 1f),
                swipe = swipe,
                longPress = longPress,
            ),
        )
    }

    /** 把用户留下的说明放在结果最前面。 */
    private fun withNotes(result: JsonObject, notes: List<String>): JsonObject {
        if (notes.isEmpty()) return result
        val content = result["content"] as? JsonArray ?: return result
        val head = notes.joinToString("\n")
        val first = content.firstOrNull() as? JsonObject
        val merged =
            if (first?.str("type") == "text") {
                listOf(
                    JsonObject(
                        first +
                            ("text" to JsonPrimitive(head + "\n" + first.str("text").orEmpty())),
                    ),
                ) + content.drop(1)
            } else {
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", head)
                    },
                ) + content
            }
        return JsonObject(result + ("content" to JsonArray(merged)))
    }

    // ---- MCP ----

    /** 处理一条 MCP 消息，返回要送回去的应答（通知类消息没有应答，返回空对象）。 */
    override fun handle(message: JsonObject): JsonObject {
        val id = message["id"] ?: return JsonObject(emptyMap())
        slot.lastUse = System.currentTimeMillis()
        val result: JsonObject? =
            when (message.str("method")) {
                "initialize" ->
                    buildJsonObject {
                        // 对话开始用手机工具：无障碍服务要是被系统关了，趁这时加回去
                        AccessibilityGuard.checkSoon("对话开始用手机工具")
                        put(
                            "protocolVersion",
                            message.obj("params")?.str("protocolVersion") ?: "2025-06-18",
                        )
                        putJsonObject("capabilities") { putJsonObject("tools") {} }
                        putJsonObject("serverInfo") {
                            put("name", SERVER)
                            put("version", "1")
                        }
                        put("instructions", PhoneToolCatalog.INSTRUCTIONS)
                    }
                "tools/list" ->
                    buildJsonObject { put("tools", JsonArray(PhoneToolCatalog.definitions)) }
                "tools/call" -> {
                    val params = message.obj("params")
                    val name = params?.str("name").orEmpty()
                    val arguments = params?.obj("arguments") ?: JsonObject(emptyMap())
                    // Claude Code 在 _meta 里带上这次调用对应的 tool_use id，胶囊据此把描述对到聊天里正在跑的那一步
                    val toolUseId = params?.obj("_meta")?.str("claudecode/toolUseId")
                    if (name in ENGAGING) engage()
                    callsInFlight.incrementAndGet()
                    try {
                        runCatching {
                            // 用户暂停或接管时先在这里等着
                            gate.await()?.let { toolFailure(it) }
                                ?: call(name, arguments, toolUseId)
                        }
                            .getOrElse { toolFailure(it.message ?: it.javaClass.simpleName) }
                            .also { DiagEvents.phoneTool(name, it) }
                    } finally {
                        callsInFlight.decrementAndGet()
                    }
                }
                "ping" -> JsonObject(emptyMap())
                else -> null
            }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result != null) put("result", result)
            else
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found")
                }
        }
    }

    private fun call(name: String, a: JsonObject, toolUseId: String?): JsonObject =
        PhoneToolGroups.find(name)?.let { group ->
            reportCall(toolUseId, name, a)
            group.call(
                CallContext(context, backend, unattended as UnattendedRun?, harness),
                name,
                a,
            )
        } ?: synchronized(lock) { callScreenTool(name, a, toolUseId) }

    /** 屏幕这一套工具的一次调用。只在持着 [lock] 时调用。 */
    private fun callScreenTool(name: String, a: JsonObject, toolUseId: String?): JsonObject {
        slot.justWoke = false
        // 用户在后台屏幕上动过手：画面可能已经不是 Claude 上次看到的样子，旧的控件编号作废
        val touched = touchedByUser.getAndSet(false)
        if (touched) forget()
        // 这次的动作是照着旧画面定的，照做可能点错：不执行，把新画面交回去
        val skip = touched && onAgentDisplay && name in BLIND_ACTIONS
        val notes = callNotes(name, touched, skip)
        reportCall(toolUseId, name, a)
        val result = if (skip) skippedAction(a) else screenTool(name, a)
        // 开应用、移到主屏之后才知道落在哪块屏上
        if (name == "open_app" || name == "show_to_user") {
            val onMainScreen = !onAgentDisplay
            main.post { actingOnMain = onMainScreen }
        }
        return withNotes(result, notes)
    }

    /** 这次调用的结果前面要说的话：用户动过后台屏幕、动作因此没执行，和用户在界面上留给 Claude 的话。 */
    private fun callNotes(name: String, touched: Boolean, skip: Boolean): List<String> = buildList {
        if (touched) add("用户刚才在后台屏幕上手动操作过，画面可能变了")
        if (skip) add("所以这次的 $name 没有执行：请按下面的新画面重新决定")
        while (true) add(userNotes.poll() ?: break)
    }

    /** 照着旧画面定的动作没有执行：交回新画面，算出错。 */
    private fun skippedAction(a: JsonObject): JsonObject =
        ready()?.let { toolFailure(it) }
            ?: JsonObject(
                observe(screenshot = a.bool("screenshot") == true, maxNodes = 60) +
                    ("isError" to JsonPrimitive(true)),
            )

    /** 按工具名分派屏幕这一套工具。 */
    private fun screenTool(name: String, a: JsonObject): JsonObject =
        when (name) {
            "screen" -> look(a)
            "tap" -> act(a) { screen, notes -> tapTimes(screen, a, notes) }
            "long_press" ->
                act(a) { screen, notes ->
                    tap(
                        screen,
                        a,
                        longPressMs = (a.int("duration_ms") ?: 700).coerceIn(300, 5000).toLong(),
                        notes,
                    )
                }
            "swipe" -> act(a) { screen, _ -> swipe(screen, a) }
            // 滚动自己等到画面停下来
            "scroll" -> act(a, settleMs = 0) { screen, notes -> scroll(screen, a, notes) }
            "type_text" -> act(a) { screen, notes -> typeText(screen, a, notes) }
            "press_key" -> act(a) { _, notes -> pressKey(a.str("key").orEmpty(), notes) }
            "wait" -> waitFor(a)
            "open_app" -> openApp(a)
            "show_to_user" -> showToUser(a)
            "screen_record" -> record(a)
            else -> toolFailure("没有这个工具：$name")
        }

    /** screen 工具：按需换屏，再看屏。 */
    private fun look(a: JsonObject): JsonObject {
        // 切到后台屏幕时也在 ready() 里唤醒
        val problem = a.str("display")?.let { switchTo(it) } ?: ready()
        if (problem != null) return toolFailure(problem)
        return observe(
            screenshot = a.bool("screenshot") == true,
            maxNodes = (a.int("max_nodes") ?: 80).coerceIn(10, 250),
            detail = true,
        )
    }

    /** screen 的 display 参数：换到主屏或后台屏幕。返回问题，没有问题返回 null。 */
    private fun switchTo(target: String): String? =
        when (target.lowercase()) {
            "main" -> {
                onAgentDisplay = false
                null
            }
            "background" ->
                if (screenRef?.alive == true) {
                    onAgentDisplay = true
                    null
                } else "现在没有后台屏幕：用 open_app 打开应用时会自动建一块"
            else -> "display 只能是 main 或 background"
        }

    // ---- 读屏 ----

    private fun screen(): Screen? {
        if (onAgentDisplay)
            return screenRef?.takeIf { it.alive }?.let { Screen(it.width, it.height) }
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
        ZetaAccessibilityService.instance?.let {
            return it
        }
        // 开关开着但服务没连上（刚装好、被系统断开）：用 root 把它打开
        if (!AccessibilityGuard.enable(context)) return null
        return ZetaAccessibilityService.instance
    }

    private fun stateLine(screen: Screen): String {
        val notes = if (onAgentDisplay) emptyList() else mainScreenNotes()
        val where = if (onAgentDisplay) "后台屏幕（display $display，用户看不到，除非在 Zeta 里打开查看）" else "主屏"
        val landscape = if (screen.width > screen.height) "，横屏" else ""
        return "$where ${screen.modelWidth}×${screen.modelHeight}（截图坐标）$landscape" +
            notes.joinToString("") { "；$it" }
    }

    /** 在主屏上操作时状态行补的几句：屏幕关着、锁着屏、后台屏幕上还开着应用。 */
    private fun mainScreenNotes(): List<String> {
        val power = context.getSystemService(PowerManager::class.java)
        val guard = context.getSystemService(KeyguardManager::class.java)
        val other = screenRef?.takeIf { it.alive }
        return buildList {
            if (display == 0 && power?.isInteractive == false)
                add("屏幕是关着的（可以用 shell 执行 input keyevent WAKEUP）")
            if (display == 0 && guard?.isKeyguardLocked == true) add("手机处于锁屏状态")
            if (other != null) {
                add(
                    "后台屏幕上还开着" +
                        (agentDisplayApp?.let { " $it" } ?: "应用") +
                        (if (other.asleep.value) "，在休眠" else "") +
                        "（screen 传 display: background 切过去）",
                )
            }
        }
    }

    /** 当前屏幕最上面的界面（`包名/.类名`），经 root 读任务栈。 */
    private fun topActivity(): String? {
        val tasks = rootTasks().filter { it.displayId == display }
        return (tasks.firstOrNull { it.visible } ?: tasks.firstOrNull())
            ?.topActivity
            ?.let(SystemOutput::shortComponent)
    }

    /** [detail]：screen 主动看屏时多报前台的界面（要多跑一次 root 命令，动作之后的观察不报）。 */
    private fun describe(screen: Screen, maxNodes: Int, detail: Boolean = false): String {
        val service =
            service()
                ?: run {
                    forget()
                    return stateLine(screen) +
                        "\n无障碍服务没有开启，读不到控件列表（只能靠截图和坐标操作）。请用户到 Zeta 的 设置 → 手机操作 里开启。"
                }
        val listing = ControlListing(screen, maxNodes)
        // 读最新的：缓存里的位置可能停在页面切换动画的半路上
        service.dropCache()
        for (root in service.roots(display)) listing.visit(root, 0)
        latest = listing.found
        // 最上层的应用窗口比第一个控件准：主屏上排在最前的是状态栏
        val front =
            (if (detail) topActivity() else null) ?: service.topApp(display) ?: listing.packageName
        return buildString {
            append(stateLine(screen))
            front?.let { append("；前台应用 ").append(it) }
            if (service.imeShown(display)) append("；输入法键盘开着")
            append('\n')
            if (listing.lines.isEmpty()) append("（没有读到控件。可能是游戏、视频这类自绘界面，用 screenshot=true 看截图。）")
            else append(listing.lines.joinToString("\n"))
            if (listing.truncated)
                append("\n…控件太多，只列了前 ").append(maxNodes).append(" 个（可以调大 max_nodes，或滚动后再看）")
        }
    }

    /** describe 走一遍控件树时攒下的：按编号操作用的控件、给 Claude 的每一行、截没截断、第一个列出的控件所在的包。 */
    private class ControlListing(private val screen: Screen, private val maxNodes: Int) {
        val found = ArrayList<Seen>()
        val lines = ArrayList<String>()
        var truncated = false
        var packageName: String? = null

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60) return
            if (!node.isVisibleToUser) return
            val label = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (isInteresting(node, label, description)) {
                if (found.size >= maxNodes) {
                    truncated = true
                    return
                }
                add(node, label, description)
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }

        /** 列出一个控件：没有大小的不列。 */
        private fun add(node: AccessibilityNodeInfo, label: String, description: String) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            if (packageName == null) packageName = node.packageName?.toString()
            lines += line(node, label, description, bounds)
            found += Seen(node, bounds)
        }

        /** 控件列表里的一行：编号、类名、文字、描述、id、中心位置（截图坐标）和状态。 */
        private fun line(
            node: AccessibilityNodeInfo,
            label: String,
            description: String,
            bounds: Rect,
        ): String = buildString {
            append('[').append(found.size).append("] ")
            append(node.className?.toString()?.substringAfterLast('.') ?: "View")
            if (label.isNotEmpty()) append(" \"").append(clip(label)).append('"')
            if (description.isNotEmpty() && description != label)
                append(" 描述=\"").append(clip(description)).append('"')
            node.viewIdResourceName?.substringAfterLast('/')?.let { append(" id=").append(it) }
            append(" (")
                .append(screen.toModel(bounds.centerX()))
                .append(',')
                .append(screen.toModel(bounds.centerY()))
                .append(')')
            val flags = flags(node)
            if (flags.isNotEmpty()) append(' ').append(flags.joinToString("·"))
        }

        /** 有文字、有描述或者能操作的控件才列出来。 */
        private fun isInteresting(
            node: AccessibilityNodeInfo,
            label: String,
            description: String,
        ): Boolean =
            label.isNotEmpty() ||
                description.isNotEmpty() ||
                node.isClickable ||
                node.isLongClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.isCheckable

        /** 控件的状态：能怎么操作、勾没勾选、选没选中等。 */
        private fun flags(node: AccessibilityNodeInfo): List<String> {
            val flags = ArrayList<String>()
            if (node.isClickable) flags += "可点"
            if (node.isLongClickable) flags += "可长按"
            if (node.isEditable) flags += "可输入"
            if (node.isScrollable) flags += "可滚动"
            if (node.isCheckable) flags += if (node.isChecked) "已勾选" else "未勾选"
            if (node.isSelected) flags += "已选中"
            if (node.isFocused) flags += "有焦点"
            if (!node.isEnabled) flags += "不可用"
            if (node.isPassword) flags += "密码"
            return flags
        }
    }

    private fun observe(
        screenshot: Boolean,
        maxNodes: Int,
        notes: List<String> = emptyList(),
        detail: Boolean = false,
    ): JsonObject {
        val woke = if (slot.justWoke) listOf("后台屏幕刚从休眠中唤醒（应用和页面都还在）") else emptyList()
        slot.justWoke = false
        lostAgentDisplay()?.let {
            return toolFailure((woke + notes + it).joinToString("\n"))
        }
        val screen = screen() ?: return toolFailure("找不到编号为 $display 的屏幕")
        val tree = (woke + notes + describe(screen, maxNodes, detail)).joinToString("\n")
        if (!screenshot) return toolText(tree)
        val image = capture(screen) ?: return toolText("$tree\n（截图失败）")
        return buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", tree)
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("data", image)
                        put("mimeType", "image/jpeg")
                    },
                )
            }
        }
    }

    /** 截图，缩到“截图坐标”的大小，JPEG，base64。 */
    private fun capture(screen: Screen): String? {
        val raw: Bitmap =
            if (onAgentDisplay) screenRef?.capture() ?: return null
            else
                ZetaAccessibilityService.instance?.screenshot(display)
                    ?: run {
                        // 没有无障碍服务时，主屏可以经 root 截
                        if (display != 0) return null
                        ScreenCapture.rootScreencap() ?: return null
                    }
        val scaled =
            if (screen.scale < 1f)
                Bitmap.createScaledBitmap(raw, screen.modelWidth, screen.modelHeight, true)
            else raw
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---- 操作 ----

    /** 按文字或 id 没找到控件：不算动作失败到底，把当前画面一并交回去，省得 Claude 再看一次屏。 */
    private class Missing(message: String) : Exception(message)

    /** 做一个动作，稍等界面稳定，然后把新的控件列表一并交回去（省得模型每次都再问一遍）。 动作可以往 notes 里加几句，放在新画面前面。 */
    private fun act(
        a: JsonObject,
        settleMs: Long = 500,
        action: (Screen, MutableList<String>) -> String?,
    ): JsonObject {
        ready()?.let {
            return toolFailure(it)
        }
        val screen = screen() ?: return toolFailure("找不到编号为 $display 的屏幕")
        val notes = ArrayList<String>()
        val error =
            try {
                action(screen, notes)
            } catch (missing: Missing) {
                val seen =
                    observe(
                        screenshot = a.bool("screenshot") == true,
                        maxNodes = 60,
                        notes = notes + missing.message.orEmpty(),
                    )
                return JsonObject(seen + ("isError" to JsonPrimitive(true)))
            }
        if (error != null) return toolFailure(error)
        if (settleMs > 0) {
            Thread.sleep(settleMs)
            // 页面切换的动画还在走时，读到的位置是半路上的：再等它停下来
            ZetaAccessibilityService.instance?.let { service ->
                ScreenControls.awaitStill(
                    {
                        service.dropCache()
                        service.roots(display)
                    },
                    STILL_MAX_MS,
                )
            }
        }
        return observe(screenshot = a.bool("screenshot") == true, maxNodes = 60, notes = notes)
    }

    /** 当前屏幕上按文字、id 找控件。找不到抛 [Missing]；有几个一样好的时在 [notes] 里说一句，并说清用的是哪个。 */
    private fun locate(
        screen: Screen,
        selector: ControlSelector,
        purpose: ControlPurpose,
        notes: MutableList<String>,
    ): Seen {
        val service = service() ?: throw Missing("无障碍服务没有开启，不能按文字或 id 找控件")
        // 按位置点：缓存里的位置可能停在上次页面切换的半路上
        service.dropCache()
        val found =
            ScreenControls.find(service.roots(display), selector, purpose)
                ?: throw Missing("当前屏幕上没有找到 ${selector.describe()} 的控件，这次没有执行。下面是现在的画面")
        val control = found.control
        val label =
            nodeLabel(control.node)?.let { "「$it」" }
                ?: control.node.viewIdResourceName?.substringAfterLast('/')?.let { "id=$it" }
                ?: ""
        val kind = control.node.className?.toString()?.substringAfterLast('.') ?: "View"
        val where =
            "(" +
                screen.toModel(control.bounds.centerX()) +
                "," +
                screen.toModel(control.bounds.centerY()) +
                ")"
        notes +=
            "按 ${selector.describe()} 找到 $kind$label $where" +
                if (found.ties > 1) "；共有 ${found.ties} 个一样对得上，用的是最靠前的，要别的就先 screen 再用 node" else ""
        return Seen(control.node, control.bounds)
    }

    private fun tap(
        screen: Screen,
        a: JsonObject,
        longPressMs: Long?,
        notes: MutableList<String>,
    ): String? {
        val target = ControlTarget.parse(a, allowPoint = true).let { it.value ?: return it.problem }
        val item =
            when (target) {
                is ControlTarget.Index ->
                    listed(target.index) ?: return "没有编号 ${target.index} 的控件，先用 screen 重新看一下屏幕"
                is ControlTarget.Find ->
                    locate(
                        screen,
                        target.selector,
                        if (longPressMs == null) ControlPurpose.TAP else ControlPurpose.LONG_PRESS,
                        notes,
                    )
                else -> null
            }
        // 控件自己声明可点，就让它自己处理，比模拟触摸可靠
        val clickable = item?.takeIf {
            if (longPressMs == null) it.node.isClickable else it.node.isLongClickable
        }
        // 悬浮层的光效画在要点的位置上：按控件点和退回到坐标点是同一处，只画一次
        if (clickable != null) {
            mark(
                screen,
                clickable.bounds.centerX(),
                clickable.bounds.centerY(),
                longPress = longPressMs != null,
            )
            if (clickNode(clickable, longPressMs)) return null
        }
        val (x, y) =
            item?.let { it.bounds.centerX() to it.bounds.centerY() }
                ?: (target as? ControlTarget.Point)?.let {
                    screen.toScreen(it.x) to screen.toScreen(it.y)
                }
                ?: return "要给出 node（控件编号）、match / id（按文字或 id 找控件），或者 x 和 y"
        if (clickable == null) mark(screen, x, y, longPress = longPressMs != null)
        return touchAt(x, y, longPressMs)
    }

    /** tap：按 count 点一下，或连点几下（双击）。返回问题。 */
    private fun tapTimes(screen: Screen, a: JsonObject, notes: MutableList<String>): String? {
        val count = tapCount(a).let { it.value ?: return it.problem }
        return if (count == 1) tap(screen, a, longPressMs = null, notes)
        else multiTap(screen, a, count, notes)
    }

    /**
     * 在目标上快速连点 [count] 下：要真的触摸才认得出双击，所以控件也按它的中心点，不让它自己处理点击； input 命令一下要几百毫秒，凑不成双击， 只用无障碍手势。返回问题。
     */
    private fun multiTap(
        screen: Screen,
        a: JsonObject,
        count: Int,
        notes: MutableList<String>,
    ): String? {
        val target = ControlTarget.parse(a, allowPoint = true).let { it.value ?: return it.problem }
        val (x, y) =
            when (target) {
                is ControlTarget.Index ->
                    listed(target.index)?.bounds?.let { it.centerX() to it.centerY() }
                        ?: return "没有编号 ${target.index} 的控件，先用 screen 重新看一下屏幕"
                is ControlTarget.Find ->
                    locate(screen, target.selector, ControlPurpose.TAP, notes).bounds.let {
                        it.centerX() to it.centerY()
                    }
                is ControlTarget.Point -> screen.toScreen(target.x) to screen.toScreen(target.y)
                ControlTarget.Unset -> return "要给出 node（控件编号）、match / id（按文字或 id 找控件），或者 x 和 y"
            }
        mark(screen, x, y)
        val service = service() ?: return "连点要用无障碍服务，它没有开启"
        val done = service.taps(display, x, y, MultiTap.starts(count), MultiTap.TAP_MS)
        return if (done) null else "连点没有做成（手势被系统取消了）"
    }

    /** 让控件自己处理点按或长按（[longPressMs] 不为空时）。做成了返回 true。 */
    private fun clickNode(item: Seen, longPressMs: Long?): Boolean {
        val nodeAction =
            if (longPressMs == null) AccessibilityNodeInfo.ACTION_CLICK
            else AccessibilityNodeInfo.ACTION_LONG_CLICK
        if (!runCatching { item.node.performAction(nodeAction) }.getOrDefault(false)) return false
        // 控件自己处理的点按不经过手势，主屏上的触点指示在这里补上
        ZetaAccessibilityService.instance?.indicateTap(
            display,
            item.bounds.centerX(),
            item.bounds.centerY(),
            longPressMs ?: 60,
        )
        return true
    }

    /** 在屏幕像素 ([x], [y]) 处模拟点按或长按：先用无障碍手势，不行再用 input 命令。返回问题。 */
    private fun touchAt(x: Int, y: Int, longPressMs: Long?): String? {
        val service = ZetaAccessibilityService.instance
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            if (service.gesture(display, path, longPressMs ?: 60)) return null
        }
        val command =
            if (longPressMs == null) "input -d $display tap $x $y"
            else "input -d $display swipe $x $y $x $y $longPressMs"
        return rootError(command)
    }

    private fun swipe(screen: Screen, a: JsonObject): String? {
        val x1 = a.int("x1") ?: return "缺少 x1"
        val y1 = a.int("y1") ?: return "缺少 y1"
        val x2 = a.int("x2") ?: return "缺少 x2"
        val y2 = a.int("y2") ?: return "缺少 y2"
        val duration = (a.int("duration_ms") ?: 300).coerceIn(50, 5000).toLong()
        // hold_ms：先按住再挪（拖动），到了终点也停一下再松手，放下的位置才认
        val press = GesturePlan.pressMs(a)
        return drag(
            screen,
            Stroke(
                screen.toScreen(x1),
                screen.toScreen(y1),
                screen.toScreen(x2),
                screen.toScreen(y2),
            ),
            duration,
            holdMs = if (press > 0) GesturePlan.DROP_HOLD_MS else 0,
            pressMs = press,
        )
    }

    /**
     * 沿 [stroke]（屏幕像素）滑一下：先用无障碍手势，不行再用 input 命令。[pressMs]：先在起点按住多久再走（拖动）；
     * [holdMs]：到终点后按住多久再抬手（不甩出惯性）。返回问题。
     */
    private fun drag(
        screen: Screen,
        stroke: Stroke,
        durationMs: Long,
        holdMs: Long = 0,
        pressMs: Long = 0,
    ): String? {
        val (sx1, sy1, sx2, sy2) = stroke
        mark(screen, sx1, sy1, sx2, sy2, swipe = true)
        val service = ZetaAccessibilityService.instance
        if (service != null) {
            val path =
                Path().apply {
                    moveTo(sx1.toFloat(), sy1.toFloat())
                    lineTo(sx2.toFloat(), sy2.toFloat())
                }
            if (service.gesture(display, path, durationMs, holdMs, pressMs)) return null
        }
        if (pressMs > 0) return rootError(DragCommand.motionEvents(display, stroke, pressMs))
        return rootError("input -d $display swipe $sx1 $sy1 $sx2 $sy2 $durationMs")
    }

    // ---- 录屏 ----

    /** screen_record：start 录当前屏幕（后台屏幕或主屏），stop 停下、把 mp4 交给 Claude。 */
    private fun record(a: JsonObject): JsonObject {
        if (a.str("action") != "start") return recorder.stop(a.str("action"))
        ready()?.let {
            return toolFailure(it)
        }
        val target =
            if (onAgentDisplay)
                RecordTarget.Background(
                    screenRef?.takeIf { it.alive }
                        ?: return toolFailure(lostAgentDisplay() ?: "后台屏幕没了"),
                )
            else RecordTarget.Main(mainDisplay)
        return recorder.start(target, a.int("time_limit_seconds"))
    }

    /**
     * 按内容方向滚动（down 露出下面的内容）：给了 node、match 或 id 就滚那个控件所在的列表，否则滚屏上的主列表。 滚完核对有没有真的动、动了多少、到没到头，写进
     * [notes]。
     */
    private fun scroll(screen: Screen, a: JsonObject, notes: MutableList<String>): String? {
        val direction =
            ScrollDirection.parse(a.str("direction")) ?: return "direction 要是 up、down、left 或 right"
        val amount = ScrollAmount.parse(a.str("amount")) ?: return "amount 只能是 page 或 small"
        val target =
            ControlTarget.parse(a, allowPoint = false).let { it.value ?: return it.problem }
        val service = service() ?: return "无障碍服务没有开启，滚动以后核对不了；可以先用 swipe"
        service.dropCache()
        val whole = Box(0, 0, screen.width, screen.height)
        val container =
            when (target) {
                is ControlTarget.Index,
                is ControlTarget.Find -> {
                    val item =
                        if (target is ControlTarget.Index) {
                            listed(target.index)
                                ?: return "没有编号 ${target.index} 的控件，先用 screen 重新看一下屏幕"
                        } else
                            locate(
                                screen,
                                (target as ControlTarget.Find).selector,
                                ControlPurpose.SCROLL,
                                notes,
                            )
                    // 有的列表（自绘的、包了一层的）不报可滚动：照样在整屏中间滑，看内容动没动
                    val around = ScreenControls.scrollableAround(item.node)
                    if (around == null) notes += "这个控件外面没有报可滚动的列表，改为在整屏中间滑"
                    around
                }
                else -> ScreenControls.mainScrollable(service.roots(display), direction, whole)
            }
        // 滑到头按住一下再抬手：不甩出惯性，滚多少就是手指走的那么多
        val (report, problem) =
            ScreenControls.scroll(
                { service.roots(display) },
                container,
                direction,
                amount,
                whole,
            ) { stroke, ms ->
                drag(screen, stroke, ms, holdMs = SCROLL_HOLD_MS) == null
            }
        if (report == null) return problem ?: "滚动失败"
        notes += scrollSummary(report.result, report.atEnd, direction, screen::toModel)
        return null
    }

    // ---- 等待 ----

    /** 等着的时候让出 [lock]（用户在展开画面上的手势要拿它），到点再拿回来。只在持着 [lock] 时调用。 */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun pause(ms: Long) {
        if (ms > 0) (lock as java.lang.Object).wait(ms)
    }

    /** 干等一会儿，或者等某个控件出现 / 消失、某个应用到最前面，然后交回新画面。等不到算失败（附上画面）。 用户在等的时候暂停、接管或在后台屏幕上动了手，就不再等。 */
    private fun waitFor(a: JsonObject): JsonObject {
        val request =
            WaitRequest.parse(a).let { it.value ?: return toolFailure(it.problem.orEmpty()) }
        ready()?.let {
            return toolFailure(it)
        }
        val packageName =
            (request as? WaitRequest.App)?.let {
                resolvePackage(it.app) ?: return toolFailure("没有找到应用“${it.app}”，可以用 find_apps 查一下")
            }
        val service =
            if (request !is WaitRequest.Control) null
            else service() ?: return toolFailure("无障碍服务没有开启，等不了屏幕上的变化")
        val limit =
            when (request) {
                is WaitRequest.Time -> request.ms
                is WaitRequest.Control -> request.timeoutMs
                is WaitRequest.App -> request.timeoutMs
            }
        val start = System.currentTimeMillis()
        var met = false
        var stopped: String? = null
        while (true) {
            met = waitMet(request, service, packageName)
            val elapsed = System.currentTimeMillis() - start
            if (met || elapsed >= limit) break
            stopped = waitInterrupted()
            if (stopped != null) break
            pause(minOf(WAIT_POLL_MS, limit - elapsed))
            lostAgentDisplay()?.let {
                return toolFailure(it)
            }
        }
        val seconds = "%.1f".format((System.currentTimeMillis() - start) / 1000.0)
        val summary = waitSummary(request, met, seconds, packageName)
        val seen =
            observe(
                screenshot = a.bool("screenshot") == true,
                maxNodes = 60,
                notes = listOfNotNull(summary, stopped),
            )
        return if (met || request is WaitRequest.Time) seen
        else JsonObject(seen + ("isError" to JsonPrimitive(true)))
    }

    /** wait 等的条件现在满足没有（干等的永远不满足）。等控件时 [service] 不为空，等应用时 [packageName] 是要等的包。 */
    private fun waitMet(
        request: WaitRequest,
        service: ZetaAccessibilityService?,
        packageName: String?,
    ): Boolean =
        when (request) {
            is WaitRequest.Time -> false
            is WaitRequest.Control ->
                ScreenControls.present(service!!.roots(display), request.selector) != request.gone
            is WaitRequest.App -> frontPackage() == packageName
        }

    /** 等着的时候用户暂停、接管或在后台屏幕上动了手：返回不再等的原因，没有返回 null。 */
    private fun waitInterrupted(): String? {
        // 用户暂停或接管了：不再等下去，下一次调用会在闸门前等
        if (gate.held) return "用户暂停了手机操作，没等完"
        if (onAgentDisplay && touchedByUser.getAndSet(false)) {
            forget()
            return "用户在后台屏幕上手动操作了，没等完，画面可能变了"
        }
        return null
    }

    /** wait 结束时的一句话：等了多久、等到没有。 */
    private fun waitSummary(
        request: WaitRequest,
        met: Boolean,
        seconds: String,
        packageName: String?,
    ): String =
        when (request) {
            is WaitRequest.Time -> "等了 $seconds 秒"
            is WaitRequest.Control -> {
                val what = request.selector.describe()
                when {
                    met -> "等了 $seconds 秒，$what " + if (request.gone) "已经消失" else "出现了"
                    request.gone -> "等了 $seconds 秒，$what 还在"
                    else -> "等了 $seconds 秒，$what 还没出现"
                }
            }
            is WaitRequest.App ->
                if (met) "等了 $seconds 秒，$packageName 已经在最前面"
                else "等了 $seconds 秒，最前面的还是 " + (frontPackage() ?: "空的（屏上没有应用）")
        }

    /** 当前屏幕最前面的应用，看任务栈（`am stack list`，一两百毫秒）。不读无障碍的根节点： 浏览器这类应用头一次被读时要现建控件树，一读就是好几秒。 */
    private fun frontPackage(): String? {
        val tasks = rootTasks().filter { it.displayId == display }
        val top = tasks.firstOrNull { it.visible } ?: tasks.firstOrNull() ?: return null
        return top.topActivity?.substringBefore('/') ?: top.packageName
    }

    /** 应用名或包名对应的包名：找得到唯一一个就用它；像包名的原样用（没有桌面图标的应用也能等）。 */
    private fun resolvePackage(query: String): String? {
        val found = match(query)
        found
            .firstOrNull { it.packageName == query || it.label.equals(query, ignoreCase = true) }
            ?.let {
                return it.packageName
            }
        if (found.size == 1) return found[0].packageName
        return query.takeIf { '.' in it && ' ' !in it }
    }

    private fun typeText(screen: Screen, a: JsonObject, notes: MutableList<String>): String? {
        val value = a.str("text") ?: return "缺少 text"
        val service = ZetaAccessibilityService.instance
        val target =
            ControlTarget.parse(a, allowPoint = false).let { it.value ?: return it.problem }
        val node =
            when (target) {
                is ControlTarget.Index ->
                    listed(target.index)?.node
                        ?: return "没有编号 ${target.index} 的控件，先用 screen 重新看一下屏幕"
                is ControlTarget.Find -> editableAt(screen, target.selector, notes)
                else ->
                    service?.roots(display)?.firstNotNullOfOrNull {
                        it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    }
            }
        val done = node != null && setText(node, value, append = a.bool("append") == true, notes)
        if (!done) {
            // 退路：系统的 input text 只认 ASCII
            if (node == null && service != null) return "没有找到输入框：先点一下要输入的地方，或者用 node、match、id 指明输入框"
            inputText(value)?.let {
                return it
            }
        }
        if (a.bool("submit") == true) {
            val entered =
                node != null &&
                    runCatching {
                            node.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
                            )
                        }
                        .getOrDefault(false)
            if (!entered) return rootError("input -d $display keyevent 66")
        }
        return null
    }

    /** 按文字或 id 找要输入的框。找到的是标签、搜索入口这类：填不进去，也不该退回去往别的输入框里打字，抛 [Missing]。 */
    private fun editableAt(
        screen: Screen,
        selector: ControlSelector,
        notes: MutableList<String>,
    ): AccessibilityNodeInfo {
        val node = locate(screen, selector, ControlPurpose.TYPE, notes).node
        if (!node.isEditable)
            throw Missing("按 ${selector.describe()} 找到的不是输入框，这次没有输入：先点它打开输入框，或者用 node 指定")
        return node
    }

    /** 把输入框的内容设成 [value]（[append] 时接在原有内容后面），设上了再读回来核对，对不上在 [notes] 里说。返回设没设上。 */
    private fun setText(
        node: AccessibilityNodeInfo,
        value: String,
        append: Boolean,
        notes: MutableList<String>,
    ): Boolean {
        val full = if (append) node.text?.toString().orEmpty() + value else value
        val arguments =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
            }
        val done = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
            .getOrDefault(false)
        // 读回来核对一下（提交前读：发送之类的提交会清空输入框）。密码框读不到真实内容，不核对
        if (done && !node.isPassword && runCatching { node.refresh() }.getOrDefault(false)) {
            val now = node.text?.toString()
            if (!textLanded(full, now, node.hintText?.toString())) {
                notes += "填完读回来是「" + clip(now.orEmpty()) + "」，和要填的不一样（输入框可能改了格式、限制了长度，或者没填进去）"
            }
        }
        return done
    }

    /** 输入框不接受直接填入时的退路：系统的 input text，只认 ASCII。返回问题。 */
    private fun inputText(value: String): String? {
        if (value.isEmpty()) return "这个输入框不接受直接填入，没能清空；可以长按选中后用 press_key delete"
        if (value.any { it.code > 126 || it.code < 32 }) return "这个输入框不接受直接填入，而备用的方式只能输入英文和数字"
        return rootError("input -d $display text " + LinuxEnv.quote(value.replace(" ", "%s")))
    }

    private fun pressKey(key: String, notes: MutableList<String>): String? {
        val service = ZetaAccessibilityService.instance
        val name = key.lowercase()
        if (onAgentDisplay && (name == "home" || name == "recents")) {
            return "后台屏幕没有桌面和最近任务。换应用用 open_app，回上一页用 back"
        }
        if (name == "paste") return paste(service, notes)
        // 全局动作只作用在主屏上；别的屏幕、或者没有无障碍服务时走按键事件
        if (service != null && !onAgentDisplay && display == 0) {
            val action = globalAction(name)
            if (action != null && service.performGlobalAction(action)) return null
        }
        val command = keyCommand(name) ?: return "不认识的按键：$key"
        return rootError(command)
    }

    /** 粘贴到有焦点的输入框；没有无障碍服务或者控件不接受时退回粘贴键。返回问题。 */
    private fun paste(service: ZetaAccessibilityService?, notes: MutableList<String>): String? {
        val focused =
            service?.roots(display)?.firstNotNullOfOrNull {
                it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }
        val before = focused?.text?.toString()
        val pasted =
            focused != null &&
                runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    .getOrDefault(false)
        if (!pasted)
            rootError("input -d $display keyevent 279")?.let {
                return it
            }
        // 剪贴板空着、或者锁屏时应用读不到剪贴板，粘贴什么也不发生，也不报错：读回来看看
        if (
            focused != null &&
                runCatching { focused.refresh() }.getOrDefault(false) &&
                focused.text?.toString() == before
        ) {
            notes += "粘贴以后输入框没有变化：剪贴板可能是空的，或者手机锁着屏、应用读不到剪贴板"
        }
        return null
    }

    /** press_key 的按键名对应的无障碍全局动作，没有对应的为 null。 */
    private fun globalAction(name: String): Int? =
        when (name) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> null
        }

    /** press_key 的按键名对应的 root 命令，不认识的为 null。 */
    private fun keyCommand(name: String): String? =
        when (name) {
            "back" -> "input -d $display keyevent 4"
            "home" -> "input -d $display keyevent 3"
            "recents" -> "input -d $display keyevent 187"
            "enter" -> "input -d $display keyevent 66"
            "delete" -> "input -d $display keyevent 67"
            "wake" -> "input keyevent 224"
            "notifications" -> "cmd statusbar expand-notifications"
            "quick_settings" -> "cmd statusbar expand-settings"
            else -> null
        }

    private fun match(query: String): List<InstalledApps.Launcher> =
        InstalledApps.match(context, query)

    /** 打开应用，或者把链接、intent 动作（设置页之类）交给系统，默认在后台屏幕上。 给了 app 又给了 uri / action 时，限定由这个应用处理。 */
    private fun openApp(a: JsonObject): JsonObject {
        val request =
            OpenRequest.parse(a).let { it.value ?: return toolFailure(it.problem.orEmpty()) }
        val opening =
            openingApp(request).let { it.value ?: return toolFailure(it.problem.orEmpty()) }
        val app = opening.launcher
        val appPackage = opening.packageName
        // 谁来处理：从桌面图标启动就是这个应用；intent 先问系统会交给谁（几个应用都能处理时是系统的选择界面）
        val handled =
            if (request.isIntent)
                intentHandler(request, opening).let {
                    it.value ?: return toolFailure(it.problem.orEmpty())
                }
            else OpenHandler(appPackage, app!!.label)
        val handler = handled.packageName
        val label = handled.label
        val requested = a.str("display")?.lowercase() ?: "auto"
        if (requested !in setOf("auto", "background", "main"))
            return toolFailure("display 只能是 auto、background 或 main")
        val notes = ArrayList<String>()
        val screen = if (requested != "main") backgroundFor(requested, handled, notes) else null
        val target = screen?.displayId ?: mainDisplay
        // 在后台屏幕上运行的应用先禁掉音频焦点：不会突然出声，也不打断用户在听的东西
        if (screen != null && handler != null)
            slot.audio.mute(handler, screen.displayId, ::foregroundOnMain)
        rootError(startCommand(request, opening, target))?.let {
            return toolFailure(it)
        }
        // 开到了主屏上：之前为后台屏幕禁掉的音频焦点改回去
        if (screen == null && handler != null) slot.audio.movedToMain(handler)
        onAgentDisplay = screen != null
        val opened =
            if (request.isIntent) (request.uri ?: request.action) + "（由 $label 处理）" else label
        if (screen != null) {
            notes += "已在后台屏幕上打开 $opened"
            slot.opened(screen, label)
        } else if (request.isIntent) {
            notes += "已在主屏上打开 $opened"
        }
        Thread.sleep(1500)
        return observe(screenshot = a.bool("screenshot") == true, maxNodes = 60, notes = notes)
    }

    /** open_app 指定的应用：桌面图标 [launcher]（找得到时）和包名 [packageName]。没给 app 时两样都是 null。 */
    private class OpeningApp(val launcher: InstalledApps.Launcher?, val packageName: String?)

    /** 打开的东西由谁处理：包名 [packageName]（系统的选择界面时为 null）和给 Claude 看的名字 [label]。 */
    private class OpenHandler(val packageName: String?, val label: String)

    /** 按 open_app 的 app 找应用：对得上一个就用它；只处理链接、没有桌面图标的应用按包名原样用。 */
    private fun openingApp(request: OpenRequest): Checked<OpeningApp> {
        val query = request.app ?: return Checked.ok(OpeningApp(null, null))
        val found = match(query)
        val exact = found.firstOrNull {
            it.packageName == query || it.label.equals(query, ignoreCase = true)
        }
        val app = exact ?: found.singleOrNull()
        if (app == null && found.size > 1) {
            return Checked.bad(
                "有多个应用匹配“$query”：" +
                    found.take(8).joinToString("、") { "${it.label}（${it.packageName}）" } +
                    "。请用包名指明",
            )
        }
        // 只处理链接、没有桌面图标的应用：按包名原样用
        val appPackage =
            app?.packageName
                ?: query.takeIf { request.isIntent && '.' in it && ' ' !in it }
                ?: return Checked.bad("没有找到应用“$query”，可以用 find_apps 查一下")
        return Checked.ok(OpeningApp(app, appPackage))
    }

    /** intent 先问系统会交给谁（几个应用都能处理时是系统的选择界面）。 */
    private fun intentHandler(request: OpenRequest, opening: OpeningApp): Checked<OpenHandler> {
        val appPackage = opening.packageName
        val resolve =
            "cmd package resolve-activity --brief --user 0 -a " +
                LinuxEnv.quote(request.intentAction) +
                (request.uri?.let { " -d " + LinuxEnv.quote(it) } ?: "") +
                (appPackage?.let { " -p " + LinuxEnv.quote(it) } ?: "")
        val component =
            SystemOutput.resolvedComponent(root(resolve).stdout)
                ?: return Checked.bad(
                    "没有应用能打开 " +
                        (request.uri ?: request.action) +
                        (appPackage?.let { "（限定在 $it）" } ?: ""),
                )
        val handler =
            component.substringBefore('/').takeUnless { SystemOutput.isChooser(component) }
        val label =
            if (handler == null) "选择打开方式的界面" else opening.launcher?.label ?: appLabel(handler)
        return Checked.ok(OpenHandler(handler, label))
    }

    /** display 不是 main 时开在哪块后台屏幕上：不该或建不起来就是 null（在主屏上打开），原因写进 [notes]。 */
    private fun backgroundFor(
        requested: String,
        handled: OpenHandler,
        notes: MutableList<String>,
    ): VirtualScreen? {
        val reason = mainScreenReason(requested, handled)
        if (reason != null) {
            notes += reason
            return null
        }
        return runCatching { slot.ensure(::lostAgentDisplay) }
            .getOrElse {
                notes += "后台屏幕没能建起来（${it.message ?: it.javaClass.simpleName}），改在主屏上打开"
                null
            }
    }

    /** 这次不开到后台屏幕上的原因，没有时为 null。 */
    private fun mainScreenReason(requested: String, handled: OpenHandler): String? {
        val handler = handled.packageName
        val label = handled.label
        return when {
            !settings.agentDisplay ->
                if (requested == "background") "用户在 Zeta 的设置里关掉了「在后台屏幕上操作」，在主屏上打开" else null
            handler != null &&
                handler in OpenRequest.mainOnly(homePackage(), context.packageName) ->
                "$label 只在主屏上打开"
            // 在后台屏幕上启动会把它从主屏抽走，打断用户正在做的事
            handler != null && handler == foregroundOnMain() ->
                "$label 正开在主屏前台（用户可能正在用），就在主屏上操作，不把它挪走"
            else -> null
        }
    }

    /** 在屏幕 [target] 上打开的 am start 命令：intent 交给系统，否则从桌面图标启动。 */
    private fun startCommand(request: OpenRequest, opening: OpeningApp, target: Int): String =
        if (request.isIntent) {
            "am start --user 0 --display $target -a " +
                LinuxEnv.quote(request.intentAction) +
                (request.uri?.let { " -d " + LinuxEnv.quote(it) } ?: "") +
                (opening.packageName?.let { " -p " + LinuxEnv.quote(it) } ?: "") +
                " -f 0x10000000"
        } else {
            val launcher = opening.launcher!!
            "am start --user 0 --display $target -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n " +
                LinuxEnv.quote("${launcher.packageName}/${launcher.activity}") +
                " -f 0x10200000"
        }

    /**
     * 系统默认的桌面的包名，问不到时为 null。每次现问（本进程里一次 PackageManager 查询），用户换了桌面也跟得上； 要看得到桌面，清单的 queries 里登记了
     * HOME。几个桌面都没设默认时问到的是选择界面，包名是 android。
     */
    private fun homePackage(): String? = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(
                home,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
            ?.activityInfo
            ?.packageName
    }
        .getOrNull()

    /** 应用的名字；看不到（没有桌面图标、不在可见范围里）时用包名。 */
    private fun appLabel(packageName: String): String =
        InstalledApps.launchable(context).firstOrNull { it.packageName == packageName }?.label
            ?: InstalledApps.label(context, packageName)

    /** 主屏前台的应用（用户眼前的那个）。 */
    private fun foregroundOnMain(): String? =
        rootTasks().firstOrNull { it.displayId == mainDisplay && it.visible }?.packageName

    /** 系统里的任务栈，最上层的在前（`am stack list`）。 */
    private fun rootTasks(): List<RootTask> = SystemOutput.rootTasks(root("am stack list").stdout)

    private fun showToUser(a: JsonObject): JsonObject {
        val screen =
            screenRef?.takeIf { it.alive }
                ?: return toolFailure(
                    if (onAgentDisplay) lostAgentDisplay()!! else "现在没有后台屏幕，应用本来就开在主屏上",
                )
        val label = agentDisplayApp
        val error =
            moveTopToMain(screen)
                ?: run {
                    onAgentDisplay = false
                    null
                }
        if (error != null) return toolFailure(error)
        Thread.sleep(800)
        val reason = a.str("reason")?.takeIf { it.isNotBlank() }
        return observe(
            screenshot = a.bool("screenshot") == true,
            maxNodes = 60,
            notes =
                listOf(
                    "已把" +
                        (label?.let { " $it" } ?: "后台屏幕上的应用") +
                        " 移到主屏" +
                        (reason?.let { "（$it）" } ?: "") +
                        "，接下来在主屏上操作",
                ),
        )
    }

    /** 把后台屏幕最上面的应用移到主屏；屏上没有别的应用了就把后台屏幕收掉。返回问题。 */
    private fun moveTopToMain(screen: VirtualScreen): String? {
        // 先唤醒：移过去的应用是醒着的
        if (screen.asleep.value && !screen.wake() && screen.alive) return "后台屏幕在休眠，没能唤醒"
        val task =
            rootTasks().firstOrNull { it.displayId == screen.displayId } ?: return "后台屏幕上没有应用"
        rootError("am display move-stack ${task.id} $mainDisplay")?.let {
            return it
        }
        task.packageName?.let { slot.audio.movedToMain(it) }
        if (rootTasks().none { it.displayId == screen.displayId }) slot.release()
        else slot.changed()
        return null
    }

    /** 界面上的“移到主屏”（聊天页的按钮、胶囊上的“全屏”）。Claude 下次看屏或动手时告诉它。 */
    fun moveToMainByUser() {
        thread(name = "zeta-phone-ui", isDaemon = true) {
            synchronized(lock) {
                val screen = screenRef?.takeIf { it.alive } ?: return@thread
                val label = agentDisplayApp
                if (moveTopToMain(screen) == null) {
                    onAgentDisplay = false
                    userNotes.add("用户把后台屏幕上的" + (label ?: "应用") + "移到了主屏，之后的操作在主屏上")
                }
            }
        }
    }

    /** 界面上的“关闭”。 */
    fun closeAgentDisplayByUser() = slot.releaseAsync()

    /** 一轮结束：清掉这一轮的悬浮层状态；过一阵还没再用就让后台屏幕休眠。 */
    override fun onTurnEnded() {
        resetOverlay()
        slot.onTurnEnded()
    }

    /** 对话的进程结束了：后台屏幕和上面的应用留着，只让它睡下，用 --resume 接着的 Claude 还在原来那块屏上。 控件编号跟着上一个进程作废。 */
    override fun detach() {
        slot.detach { forget() }
        resetOverlay()
    }

    /** 彻底释放（会话删了、换了环境、或已经没人用）：停下录屏（录好的照样交给 Linux），收掉后台屏幕，之后也不再建。 */
    fun destroy() {
        recorder.stopQuietly()
        slot.destroy {
            onAgentDisplay = false
            forget()
        }
        resetOverlay()
    }

    // ---- Android 侧命令 ----

    /** 在 Android 侧（全局挂载命名空间）以 root 执行。 */
    private fun root(command: String, timeoutMs: Long = 15_000) = rootShell(command, timeoutMs)

    private fun rootError(command: String): String? {
        val result = root(command)
        return if (result.exitCode == 0) null
        else
            "执行失败：" +
                result.stderr
                    .ifBlank { result.stdout }
                    .trim()
                    .take(300)
                    .ifBlank { "退出码 ${result.exitCode}" }
    }

    companion object {
        const val SERVER = "phone"

        private fun clip(value: String): String = value.replace('\n', ' ').clipChars(80, "…")

        private const val MAX_EDGE = 1568
        private const val STOPPED = "用户停止了这一轮，这次手机操作没有执行"

        /** 暂停、接管时用户补充了一句：等着的调用作废，Claude Code 才会在这个间隙把那句话交给 Claude。 */
        const val SUPPLEMENTED = "用户暂停时补充了新的要求，这次手机操作没有执行：先看用户的补充，再看清屏幕继续"
        private const val USER_TAP_MS = 55L
        private const val USER_LONG_PRESS_MS = 650L

        /** wait 隔多久看一次屏幕。 */
        private const val WAIT_POLL_MS = 300L

        /** 动作之后最多再等多久让界面停下来（页面切换的动画）。 */
        private const val STILL_MAX_MS = 1200L

        /** 滚动手势滑到头后按住多久再抬手。 */
        private const val SCROLL_HOLD_MS = 150L

        /** 看屏、动手的工具：用过就算这一轮在用手机（胶囊据此显示）。 */
        private val ENGAGING =
            setOf(
                "screen",
                "tap",
                "long_press",
                "swipe",
                "scroll",
                "type_text",
                "press_key",
                "wait",
                "open_app",
                "show_to_user",
            )

        /** 照着画面定下的动作：用户在后台屏幕上动过手之后不能照旧执行。 */
        private val BLIND_ACTIONS =
            setOf("tap", "long_press", "swipe", "scroll", "type_text", "press_key")

        /** 总是作用在主屏上的按键。 */
        private val GLOBAL_KEYS = setOf("wake", "notifications", "quick_settings")

        /** 用户在展开画面上的手势按先后一个个做（全应用同一时间只有一个胶囊）。 */
        private val userGestures by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "zeta-phone-user").apply { isDaemon = true }
            }
        }

        /** 传给 `claude --mcp-config` 的声明。 */
        val MCP_CONFIG: String = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(SERVER) {
                    put("type", "sdk")
                    put("name", SERVER)
                }
            }
        }
            .toString()
    }
}
