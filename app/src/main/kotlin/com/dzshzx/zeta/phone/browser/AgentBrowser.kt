package com.dzshzx.zeta.phone.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.MainThreadTimeout
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.phone.tools.toolFailure
import com.dzshzx.zeta.phone.tools.toolText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Zeta 的内置浏览器：全应用一个 WebView，Claude 经 `browser` 工具在屏幕外驱动它，用户在浏览器页（[com.dzshzx.zeta.ui.browser.BrowserActivity]）里看同一个页面。
 *
 * - 屏幕外：不在浏览器页上时，WebView 挂在 Zeta 自己的私有虚拟屏上的一个窗口里（[OffscreenWindow]），用户看不到，
 *   但一直有窗口、有帧，Zeta 在前台还是后台都能加载、执行脚本、截图；截图是把它画进一张位图。
 * - 全应用一个而不是每个对话一个：工具组是无状态的单例（不知道是哪个对话在调），Cookie 本来就是整个 WebView 共用的；
 *   一个页面、一个入口，用户在设置里和聊天里打开看到的都是它。几个对话同时用时按先后排队执行。
 * - 接管：用户在浏览器页上点按（或用地址栏、前进后退）就是接管，Claude 的浏览器操作在 [awaitGate] 里等，交还或离开浏览器页后继续，
 *   并在下一次结果的开头说一句“用户手动操作过，页面可能变了”。
 * - 安全：只开 http / https；不开文件和 content 访问；下载、弹窗、权限请求一律拦下，下一次结果里告诉 Claude。
 *
 * WebView 只在主线程上碰；工具调用在各自的线程上，经 [onMain] 切过去，同一时间只做一个（[op]）。
 */
@SuppressLint("StaticFieldLeak")
internal object AgentBrowser {
    // ---- 浏览器页看的状态（只在主线程上改） ----

    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    /** 用户接管着：Claude 的浏览器操作在等。 */
    var userControlling by mutableStateOf(false)
        private set

    /** Claude 正在做的浏览器操作（一句中文），没在做时为 null。 */
    var agentAction by mutableStateOf<String?>(null)
        private set

    // ---- WebView（只在主线程上碰） ----

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    private var view: WebView? = null
    private var wrapper: MutableContextWrapper? = null

    /** 浏览器页上放 WebView 的容器；不在浏览器页时为 null。 */
    private var host: ViewGroup? = null

    /** 屏幕外的窗口：WebView 不在浏览器页上时挂在这里。建不成时为 null，退回不挂窗口、自己量布局。 */
    private var offscreen: OffscreenWindow? = null

    /** 闲置太久收掉 WebView 时存下的浏览记录，下次要用时恢复。 */
    private var saved: Bundle? = null

    // ---- 加载状态（主线程写，工具线程读） ----

    /**
     * 导航开始的次数：onPageStarted 要等服务器回了话（提交）才来，所以页面自己发起的跳转在 shouldOverrideUrlLoading 里就记上，
     * Zeta 自己 loadUrl、前进后退时也先记上。[finished] 是 onPageFinished 的次数。
     */
    @Volatile private var started = 0L
    @Volatile private var finished = 0L

    /** 主页面在加载：导航开始时置上，onPageFinished、进度到 100 或转成下载时清掉。 */
    @Volatile private var busyLoading = false
    @Volatile private var pageError: String? = null
    @Volatile private var httpStatus = 0

    /** 下一个元素编号：跨页面递增。 */
    @Volatile private var nextId = 1

    /** 页面自己做了 Claude 不知道的事（弹窗、下载、被拦下的跳转、用户清了数据……），下一次结果开头告诉它。 */
    private val notes = ConcurrentLinkedQueue<String>()

    /** 用户动过浏览器：下一次结果开头告诉 Claude 页面可能变了。 */
    private val touched = AtomicBoolean(false)

    /** 同一时间只执行一个 Claude 的浏览器操作。公平锁：几个对话一起用时按先后排队。 */
    private val op = ReentrantLock(true)

    /** 接管的闸门：工具线程在 [awaitGate] 里等，主线程开关。 */
    private val gate = ReentrantLock()
    private val gateChanged = gate.newCondition()
    private var held = false
    private var generation = 0
    private var cancelReason = ""

    private val idleRelease = Runnable { releaseIfIdle() }

    // ---- Claude 的调用 ----

    /** 执行一次 browser 调用（工具线程上，可以阻塞）。 */
    fun execute(context: Context, input: JsonObject): JsonObject {
        app = context.applicationContext
        val (request, problem) = BrowserRequest.parse(input)
        if (request == null) return toolFailure(problem ?: "参数不对")
        return op.withLock {
            // 用户接管着就先等着；排在别的调用后面时也是拿到锁以后再看，免得排队期间用户接管了却没等
            awaitGate()?.let { return@withLock toolFailure(it) }
            main.removeCallbacks(idleRelease)
            val head = buildList {
                if (touched.getAndSet(false)) add("用户刚才在浏览器里手动操作过，页面可能变了（下面是现在的样子）")
                while (true) add(notes.poll() ?: break)
            }
            val label = BrowserRequest.title(input)
            main.post { agentAction = label }
            try {
                val result = runCatching { perform(request) }.getOrElse { failure ->
                    toolFailure((failure as? BrowserFailure)?.message ?: ("浏览器出错：" + (failure.message ?: failure.javaClass.simpleName)))
                }
                // 这次操作中途冒出来的说明（弹窗、拦下的下载）也一并交回去
                val late = buildList { while (true) add(notes.poll() ?: break) }
                withNotes(result, head, late)
            } finally {
                main.post {
                    agentAction = null
                    scheduleIdle()
                }
            }
        }
    }

    private fun perform(r: BrowserRequest): JsonObject = when (r.action) {
        "open" -> open(BrowserUrls.forAgent(r.url).first!!)
        "read" -> read(r.offset)
        "click" -> click(r)
        "type" -> type(r)
        "scroll" -> scroll(r)
        "back", "forward" -> history(r.action == "back")
        "reload" -> reload()
        "screenshot" -> screenshot()
        "wait" -> waitFor(r)
        else -> toolFailure("没有这个操作：${r.action}")
    }

    private fun open(target: String): JsonObject {
        val f0 = finished
        val sameDocument = onMain {
            val v = ensureView()
            val current = v.url.orEmpty()
            // 只改 # 后面的部分不会重新加载页面，也不会有 onPageFinished
            val same = '#' in target && current.substringBefore('#') == target.substringBefore('#')
            if (!same) navigationStarting()
            v.loadUrl(target)
            same
        }
        val note = if (sameDocument) null.also { Thread.sleep(300) } else waitForLoad(f0, LOAD_TIMEOUT_MS, requireFinish = true)
        pageError?.let { return toolFailure("打不开 $target：$it") }
        settle()
        return pageView(listOfNotNull(note, httpNote()))
    }

    private fun read(offset: Int): JsonObject {
        requirePage()
        val value = evaluate(BrowserScripts.read())
        value.str("error")?.let { throw BrowserFailure("读不到页面文字：$it") }
        val head = "「" + BrowserPage.clip(value.str("title").orEmpty().ifBlank { "无标题" }, 100) + "」 " + value.str("url").orEmpty() +
            (if (value.bool("part") == true) "（正文区）" else "") + "\n\n"
        return toolText(head + BrowserPage.readSlice(value.str("text").orEmpty(), offset))
    }

    private fun click(r: BrowserRequest): JsonObject {
        requirePage()
        val point = if (r.element == null) {
            val size = shotSize()
            size.fraction(r.x!!, r.y!!) ?: return toolFailure("(${r.x}, ${r.y}) 在截图外面：截图是 ${size.width}×${size.height}")
        } else null
        val s0 = started
        val f0 = finished
        val value = evaluate(BrowserScripts.click(r.element, point?.first, point?.second))
        missing(value, r.element)?.let { return it }
        value.str("error")?.let { return toolFailure(if (it == "nothing") "(${r.x}, ${r.y}) 那里没有元素" else "点按失败：$it") }
        val what = (value["what"] as? JsonObject)?.let(::describeTarget).orEmpty()
        val head = buildList {
            add("点按了 $what".trim())
            if (value.bool("covered") == true) add("（这个元素被别的东西挡着，点按是直接发给它的；如果没反应，先关掉挡着的弹层）")
        }
        afterAction(s0, f0)?.let { return pageView(head + it) }
        return pageView(head + listOfNotNull(httpNote()))
    }

    private fun type(r: BrowserRequest): JsonObject {
        requirePage()
        val s0 = started
        val f0 = finished
        val value = evaluate(BrowserScripts.type(r.element, r.text.orEmpty(), r.submit))
        missing(value, r.element)?.let { return it }
        val what = (value["what"] as? JsonObject)?.let(::describeTarget).orEmpty()
        when (value.str("error")) {
            null -> Unit
            "nofocus" -> return toolFailure("没有给 element，页面上也没有获得焦点的输入框")
            "noteditable" -> return toolFailure("$what 不能输入文字（复选框、按钮这类用 click）")
            "readonly" -> return toolFailure("$what 是只读或不可用的")
            "option" -> {
                val options = (value["options"] as? JsonArray)?.joinToString("、") { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
                return toolFailure("$what 里没有叫“${r.text}”的选项。可选：$options")
            }
            else -> return toolFailure("输入失败：" + value.str("error"))
        }
        val head = listOf(
            value.str("selected")?.let { "在 $what 里选了“$it”" }
                ?: ("在 $what 里输入了 ${r.text.orEmpty().length} 个字" + if (r.submit) "并按了回车" + (if (value.bool("submitted") == true) "（提交了表单）" else "") else ""),
        )
        if (r.submit) afterAction(s0, f0)?.let { return pageView(head + it) } else Thread.sleep(250)
        return pageView(head)
    }

    private fun scroll(r: BrowserRequest): JsonObject {
        requirePage()
        val value = evaluate(BrowserScripts.scroll(r.element, r.direction != "up"))
        missing(value, r.element)?.let { return it }
        value.str("error")?.let { return toolFailure("滚动失败：$it") }
        val note = when {
            r.element != null -> "把 " + (value["what"] as? JsonObject)?.let(::describeTarget).orEmpty() + " 滚到了视口中间"
            value.int("moved") == 0 -> if (r.direction == "up") "已经在最上面了，没有滚动" else "已经到底了，没有滚动（如果内容还没出完，可能在加载，稍等再看）"
            value.bool("inner") == true -> "滚动了页面里的滚动区"
            else -> null
        }
        Thread.sleep(300)
        settle(1500)
        return pageView(listOfNotNull(note))
    }

    private fun history(back: Boolean): JsonObject {
        requirePage()
        val s0 = started
        val f0 = finished
        val moved = onMain {
            val v = ensureView()
            if (if (back) v.canGoBack() else v.canGoForward()) {
                navigationStarting()
                if (back) v.goBack() else v.goForward()
                true
            } else false
        }
        if (!moved) return toolFailure(if (back) "没有可以后退的页面" else "没有可以前进的页面")
        val note = afterAction(s0, f0)
        return pageView(listOfNotNull(if (back) "后退了一页" else "前进了一页", note))
    }

    private fun reload(): JsonObject {
        requirePage()
        val f0 = finished
        onMain {
            navigationStarting()
            ensureView().reload()
        }
        val note = waitForLoad(f0, LOAD_TIMEOUT_MS, requireFinish = true)
        settle()
        return pageView(listOfNotNull("刷新了页面", note, httpNote()))
    }

    private fun waitFor(r: BrowserRequest): JsonObject {
        requirePage()
        val text = r.text?.takeIf { it.isNotBlank() }
        if (text == null) {
            Thread.sleep(r.ms.takeIf { it > 0 }?.coerceIn(100, BrowserLimits.WAIT_MS)?.toLong() ?: 1000L)
            if (loadingNow()) waitForLoad(finished, BrowserLimits.WAIT_MS.toLong(), requireFinish = false)
            settle(1500)
            return pageView(emptyList())
        }
        val timeout = (r.ms.takeIf { it > 0 } ?: 10_000).coerceIn(500, BrowserLimits.WAIT_MS)
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching { evaluate(BrowserScripts.contains(text), 3_000).bool("found") }.getOrNull()
            if (found == true) {
                settle(1000)
                return pageView(listOf("页面上出现了「${BrowserPage.clip(text, 40)}」"))
            }
            Thread.sleep(400)
        }
        return JsonObject(pageView(listOf("等了 ${timeout / 1000.0} 秒，页面上没有出现「${BrowserPage.clip(text, 40)}」")) + ("isError" to JsonPrimitive(true)))
    }

    private fun screenshot(): JsonObject {
        requirePage()
        val bitmap = onMain { capture() } ?: return toolFailure("截图失败：页面还没有画出来")
        val size = shotSize()
        val scaled = if (size.scale < 1f) Bitmap.createScaledBitmap(bitmap, size.width, size.height, true).also { bitmap.recycle() } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
        scaled.recycle()
        val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "截图 ${size.width}×${size.height}（这是视口；click 的 x、y 用这张图上的像素）")
                })
                add(buildJsonObject {
                    put("type", "image")
                    put("data", data)
                    put("mimeType", "image/jpeg")
                })
            }
        }
    }

    /** 页面概况（给 Claude 的标准结果）。[head]：放在最前面的几句。 */
    private fun pageView(head: List<String>): JsonObject {
        // 动作已经做了，读概况失败不算这次失败：说一句，让 Claude 稍后再看
        val value = try {
            evaluate(BrowserScripts.snapshot(nextId, BrowserLimits.VIEW_TEXT + 500))
        } catch (failure: BrowserFailure) {
            return toolText((head + "（读不到页面概况：${failure.message}。稍后用 wait 或 screenshot 再看）").joinToString("\n"))
        }
        value.str("error")?.let { return toolText((head + "（读不到页面概况：$it）").joinToString("\n")) }
        val page = BrowserPage.parseSnapshot(value)
        if (page.next > nextId) nextId = page.next
        return toolText((head + BrowserPage.render(page, loadingNow())).joinToString("\n"))
    }

    /** 按编号找的元素不在了：说清楚，并交回新的页面概况。 */
    private fun missing(value: JsonObject, element: Int?): JsonObject? {
        if (value.str("error") != "gone") return null
        val view = pageView(listOf("元素 [$element] 已经不在页面上了（页面变了，或者编号是别的页面的），这次没有执行。按下面的新编号重新选："))
        return JsonObject(view + ("isError" to JsonPrimitive(true)))
    }

    private fun describeTarget(e: JsonObject): String = BrowserPage.element(e)?.let(BrowserPage::line).orEmpty()

    private fun httpNote(): String? = httpStatus.takeIf { it >= 400 }?.let { "网页返回 HTTP $it" }

    /** 还没有页面时不能读、点、截图。闲置被收掉的先恢复。 */
    private fun requirePage() {
        val restoring = onMain {
            if (view == null && saved != null) {
                val f0 = finished
                navigationStarting()
                ensureView()
                f0
            } else null
        }
        if (restoring != null) {
            notes.add("浏览器闲置后休眠过，页面重新载入了")
            waitForLoad(restoring, LOAD_TIMEOUT_MS, requireFinish = true)
            settle()
        }
        val current = onMain { view?.url }
        if (current.isNullOrBlank() || current == "about:blank") throw BrowserFailure("浏览器里还没有打开网页，先用 open")
    }

    private fun loadingNow(): Boolean = busyLoading

    /** 导航开始（主线程上）：先记上，onPageStarted 要等服务器回话才来；上一个页面的错误和 HTTP 状态清掉。 */
    private fun navigationStarting() {
        started++
        busyLoading = true
        pageError = null
        httpStatus = 0
    }

    /**
     * 等导航加载完（[f0] 是导航前 onPageFinished 的次数）。[requireFinish]：一定要等到 onPageFinished（Zeta 自己 loadUrl 的，它总会来）；
     * 否则（页面自己的跳转，可能变成下载、被拦下）不再加载 0.6 秒也算完。超时不算失败：返回一句说明，照样交回当前的样子。
     */
    private fun waitForLoad(f0: Long, timeoutMs: Long, requireFinish: Boolean): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var idleSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val loadingNow = loadingNow()
            if (finished > f0 && !loadingNow) return null
            if (!requireFinish) {
                if (loadingNow) idleSince = 0L
                else if (idleSince == 0L) idleSince = System.currentTimeMillis()
                else if (System.currentTimeMillis() - idleSince >= 600) return null
            }
            Thread.sleep(100)
        }
        return "页面 ${timeoutMs / 1000} 秒还没加载完，下面是目前的样子"
    }

    /** 动作之后：要是引起了导航就等加载完，再等页面停下来。返回要告诉 Claude 的一句（超时），没有就是 null。 */
    private fun afterAction(s0: Long, f0: Long): String? {
        Thread.sleep(350)
        val note = if (started > s0 || loadingNow()) waitForLoad(f0, LOAD_TIMEOUT_MS, requireFinish = false) else null
        settle()
        return note
    }

    /** 等页面停下来：连续两次指纹一样，或者到时间。 */
    private fun settle(maxMs: Long = 2_500) {
        val deadline = System.currentTimeMillis() + maxMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (loadingNow()) {
                Thread.sleep(150)
                continue
            }
            val sig = runCatching { evaluate(BrowserScripts.signature(), 2_000).str("sig") }.getOrNull()
            if (sig != null && sig == last) return
            last = sig
            Thread.sleep(300)
        }
    }

    /** 在页面里执行一段脚本，等它的结果（工具线程上）。 */
    private fun evaluate(script: String, timeoutMs: Long = SCRIPT_TIMEOUT_MS): JsonObject {
        val future = CompletableFuture<String?>()
        onMain {
            val v = view ?: throw BrowserFailure("浏览器的页面已经关掉了（用户清除了数据，或者渲染进程退出了），重新 open 吧")
            v.evaluateJavascript(script) { future.complete(it) }
        }
        val raw = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw BrowserFailure("页面 ${timeoutMs / 1000} 秒没有响应脚本（可能卡住了，或者正在跳转）")
        }
        return BrowserPage.decode(raw) ?: throw BrowserFailure("页面没有返回结果（可能正在跳转），稍后再试")
    }

    private fun shotSize(): ShotSize = onMain {
        val v = ensureView()
        ShotSize(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1))
    }

    /** 把当前视口画进位图（主线程上）。 */
    private fun capture(): Bitmap? {
        val v = view ?: return null
        if (v.parent == null) layoutDetached(v)
        if (v.width <= 0 || v.height <= 0) return null
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        v.draw(canvas)
        return bitmap
    }

    private fun withNotes(result: JsonObject, head: List<String>, tail: List<String>): JsonObject {
        if (head.isEmpty() && tail.isEmpty()) return result
        val content = result["content"] as? JsonArray ?: return result
        val first = content.firstOrNull() as? JsonObject
        val merged = if (first?.str("type") == "text") {
            val text = (head + first.str("text").orEmpty() + tail.map { "\n$it" }).joinToString("\n").trim()
            listOf(JsonObject(first + ("text" to JsonPrimitive(text)))) + content.drop(1)
        } else {
            listOf(buildJsonObject { put("type", "text"); put("text", (head + tail).joinToString("\n")) }) + content
        }
        return JsonObject(result + ("content" to JsonArray(merged)))
    }

    // ---- 接管 ----

    /** 用户接管着时等（工具线程上）。返回不该执行的原因，可以执行时返回 null。 */
    private fun awaitGate(): String? = gate.withLock {
        if (!held) return null
        val mine = generation
        var left = TimeUnit.MINUTES.toNanos(GATE_TIMEOUT_MINUTES)
        while (held && generation == mine) {
            if (left <= 0) {
                return "用户接管了浏览器，$GATE_TIMEOUT_MINUTES 分钟内没有交还，这次操作没有执行。先别再用浏览器，在回复里告诉用户做到哪一步了"
            }
            left = gateChanged.awaitNanos(left)
        }
        if (generation != mine) cancelReason else null
    }

    private fun setControl(control: Boolean) {
        userControlling = control
        gate.withLock {
            held = control
            gateChanged.signalAll()
        }
    }

    /** 用户在浏览器页上动手了（主线程上）：接管，Claude 的浏览器操作先等着。 */
    fun takeOverByUser() {
        touched.set(true)
        if (!userControlling) setControl(true)
    }

    /** 交还给 Claude（主线程上）。 */
    fun handBackByUser() {
        if (userControlling) setControl(false)
    }

    /** 用户停止了这一轮：在闸门前等着的浏览器操作作废（任何线程）。几个对话共用一个浏览器，别的对话等着的也一并作废，它们会收到这句说明。 */
    fun cancelWaiting(reason: String = "用户停止了操作，这次浏览器操作没有执行") {
        gate.withLock {
            if (!held) return
            generation++
            cancelReason = reason
            gateChanged.signalAll()
        }
    }

    // ---- 浏览器页的操作（主线程上） ----

    /** 地址栏：网址或搜索词。返回问题，没有问题返回 null。 */
    fun navigateByUser(context: Context, input: String): String? {
        val target = BrowserUrls.fromAddressBar(input) ?: return "只能打开 http / https 网址"
        app = context.applicationContext
        takeOverByUser()
        val v = ensureView()
        navigationStarting()
        v.loadUrl(target)
        return null
    }

    fun backByUser() {
        takeOverByUser()
        view?.takeIf { it.canGoBack() }?.goBack()
    }

    fun forwardByUser() {
        takeOverByUser()
        view?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reloadOrStopByUser() {
        takeOverByUser()
        val v = view ?: return
        if (loading) v.stopLoading() else v.reload()
    }

    /** 清除浏览数据：Cookie、网站存储、缓存、表单和 HTTP 认证记录，页面关掉。[done] 在主线程上回调。 */
    fun clearDataByUser(context: Context, done: () -> Unit) {
        app = context.applicationContext
        val container = host
        destroyView()
        saved = null
        resetState()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context.applicationContext).clearHttpAuthUsernamePassword()
        // 清缓存要借一个 WebView（缓存是全应用共用的）
        runCatching { WebView(context.applicationContext).apply { clearCache(true) }.destroy() }
        notes.add("用户清除了浏览器数据（Cookie、登录状态、缓存都没了），之前的页面已关闭")
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            // 还在浏览器页上：放一个新的空白 WebView
            if (container != null && host === container) attach(container, container.context)
            done()
        }
    }

    /** 浏览器页打开：把 WebView 放进 [container]（主线程上）。还没有 WebView 就建一个（闲置收掉的会恢复上次的页面）。 */
    fun attach(container: ViewGroup, activity: Context) {
        app = activity.applicationContext
        main.removeCallbacks(idleRelease)
        host = container
        val v = ensureView()
        wrapper?.baseContext = activity
        (v.parent as? ViewGroup)?.takeIf { it !== container }?.removeView(v)
        if (v.parent == null) container.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // 跟着窗口走硬件加速
        v.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /** 浏览器页关了（主线程上）：WebView 回到屏幕外；用户接管着就交还。 */
    fun detach(container: ViewGroup) {
        if (host !== container) return
        host = null
        view?.let(::park)
        handBackByUser()
        scheduleIdle()
    }

    /** 浏览器页要显示的：现在有没有页面。 */
    fun hasPage(): Boolean = url.isNotBlank() && url != "about:blank"

    // ---- WebView 的生命周期（主线程上） ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView {
        view?.let { return it }
        val context = app ?: throw BrowserFailure("浏览器还没有初始化")
        val wrap = MutableContextWrapper(context)
        val v = WebView(wrap)
        v.setBackgroundColor(Color.WHITE)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        v.webViewClient = Client()
        v.webChromeClient = Chrome()
        v.setDownloadListener { link, _, disposition, mime, length ->
            // 跳转变成了下载：不会再有 onPageFinished
            busyLoading = false
            val name = URLUtil.guessFileName(link, disposition, mime)
            val size = if (length > 0) "，${length / 1024} KB" else ""
            notes.add("页面要下载文件「$name」（$mime$size），已拦下：浏览器不下载文件。需要的话把链接告诉用户：" + BrowserUrls.describe(link))
        }
        wrapper = wrap
        view = v
        // 浏览器页开着就由 attach 放过去；否则先放进屏幕外的窗口
        if (host == null) park(v)
        saved?.let {
            v.restoreState(it)
            saved = null
        }
        return v
    }

    /** 屏幕外的视口大小：手机屏幕的宽，高度最多 2400。 */
    private fun offscreenSize(context: Context): Triple<Int, Int, Int> {
        val metrics = context.resources.displayMetrics
        return Triple(metrics.widthPixels.coerceIn(720, 1440), metrics.heightPixels.coerceIn(1280, 2400), metrics.densityDpi)
    }

    /** 把 WebView 放进屏幕外的窗口（主线程上）。窗口建不成就不挂窗口，自己量好布局。 */
    private fun park(v: WebView) {
        val context = app ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        val window = offscreen ?: offscreenSize(context).let { (w, h, dpi) -> OffscreenWindow.create(context, w, h, dpi) }?.also { offscreen = it }
        if (window == null) {
            wrapper?.baseContext = context
            layoutDetached(v)
            return
        }
        wrapper?.baseContext = window.context
        window.container.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** 没有窗口可挂时：按屏幕外视口的大小量好布局（这时 Chromium 可能不出新帧，截图会停在旧画面上）。 */
    private fun layoutDetached(v: WebView) {
        val (width, height) = offscreenSize(app ?: return)
        if (v.width == width && v.height == height) return
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, width, height)
    }

    private fun destroyView() {
        val v = view ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        runCatching { v.stopLoading() }
        runCatching { v.destroy() }
        view = null
        wrapper = null
        busyLoading = false
    }

    private fun resetState() {
        url = ""
        title = ""
        loading = false
        progress = 0
        canGoBack = false
        canGoForward = false
        pageError = null
        httpStatus = 0
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    /** 闲置太久：存下浏览记录，收掉 WebView（连同它的渲染进程），下次要用时恢复。浏览器页开着或正在操作时不收。 */
    private fun releaseIfIdle() {
        val v = view ?: return
        if (host != null || op.isLocked) return scheduleIdle()
        saved = Bundle().also { v.saveState(it) }
        destroyView()
        loading = false
        offscreen?.release()
        offscreen = null
    }

    private fun updateHistory(v: WebView) {
        canGoBack = v.canGoBack()
        canGoForward = v.canGoForward()
    }

    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
            val link = request.url.toString()
            if (BrowserUrls.allowed(link)) {
                // 页面自己发起的跳转：现在就记上开始，好让动作之后知道要等加载
                if (request.isForMainFrame && v === view) navigationStarting()
                return false
            }
            if (request.isForMainFrame) notes.add("页面想打开 ${BrowserUrls.describe(link)}，已拦下（浏览器只开 http / https 网页）")
            return true
        }

        override fun onPageStarted(v: WebView, link: String?, favicon: Bitmap?) {
            if (v !== view) return
            busyLoading = true
            loading = true
            url = link.orEmpty()
            updateHistory(v)
        }

        override fun onPageFinished(v: WebView, link: String?) {
            if (v !== view) return
            finished++
            busyLoading = false
            loading = false
            url = link.orEmpty()
            title = v.title.orEmpty()
            updateHistory(v)
        }

        override fun doUpdateVisitedHistory(v: WebView, link: String?, isReload: Boolean) {
            if (v !== view) return
            url = link.orEmpty()
            updateHistory(v)
        }

        override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (v !== view || !request.isForMainFrame) return
            pageError = error.description?.toString()?.ifBlank { null } ?: "错误 ${error.errorCode}"
        }

        override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (v !== view || !request.isForMainFrame) return
            httpStatus = response.statusCode
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (v === view) notes.add("${BrowserUrls.describe(error.url)} 的证书有问题，已拒绝连接")
        }

        override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (v !== view) return true
            val last = url
            destroyView()
            resetState()
            notes.add("网页的渲染进程" + (if (detail.didCrash()) "崩溃了" else "被系统收掉了") + "，页面已关闭" + (if (last.isNotBlank()) "（之前在 ${BrowserUrls.describe(last)}）" else "") + "，要继续请重新 open")
            // 浏览器页还开着：放一个新的空白 WebView
            host?.let { attach(it, it.context) }
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onProgressChanged(v: WebView, newProgress: Int) {
            if (v !== view) return
            progress = newProgress
            if (newProgress >= 100) busyLoading = false
        }

        override fun onReceivedTitle(v: WebView, value: String?) {
            if (v === view) title = value.orEmpty()
        }

        /** 用户在浏览器页上自己操作时，弹窗照常显示给他；屏幕外（Claude 在用）时自动处理，告诉 Claude。 */
        private fun userSees(): Boolean = host != null && userControlling

        override fun onJsAlert(v: WebView, link: String?, message: String?, result: JsResult): Boolean {
            if (userSees()) return false
            notes.add("页面弹出提示：" + BrowserPage.clip(message.orEmpty(), 300) + "（已自动关掉）")
            result.confirm()
            return true
        }

        override fun onJsConfirm(v: WebView, link: String?, message: String?, result: JsResult): Boolean {
            if (userSees()) return false
            notes.add("页面弹出确认框：" + BrowserPage.clip(message.orEmpty(), 300) + "（已自动选了“取消”；要确定的话请用户在 Zeta 的浏览器页里自己操作）")
            result.cancel()
            return true
        }

        override fun onJsPrompt(v: WebView, link: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (userSees()) return false
            notes.add("页面弹出输入框：" + BrowserPage.clip(message.orEmpty(), 300) + "（已自动取消）")
            result.cancel()
            return true
        }

        override fun onJsBeforeUnload(v: WebView, link: String?, message: String?, result: JsResult): Boolean {
            if (userSees()) return false
            result.confirm()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            notes.add("页面要求使用 " + request.resources.joinToString("、") { it.substringAfterLast('.') } + "，已拒绝")
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            notes.add("页面要选择文件上传，浏览器不支持上传文件")
            callback.onReceiveValue(null)
            return true
        }
    }

    // ---- 线程 ----

    /** 在主线程上执行并等结果（工具线程上调用）；主线程没响应时报 [BrowserFailure]，原话交给 Claude。 */
    private fun <T> onMain(block: () -> T): T = try {
        com.dzshzx.zeta.core.onMain(MAIN_TIMEOUT_MS, block)
    } catch (_: MainThreadTimeout) {
        throw BrowserFailure("Zeta 的界面线程没有响应，稍后再试")
    }

    private class BrowserFailure(message: String) : RuntimeException(message)

    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val SCRIPT_TIMEOUT_MS = 8_000L
    private const val MAIN_TIMEOUT_MS = 10_000L
    private const val GATE_TIMEOUT_MINUTES = 30L
    private const val SHOT_QUALITY = 75

    /** 这么久没用、浏览器页也没开，就收掉 WebView。 */
    private const val IDLE_RELEASE_MS = 15 * 60_000L
}
