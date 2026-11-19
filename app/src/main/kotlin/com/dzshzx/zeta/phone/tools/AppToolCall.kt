package com.dzshzx.zeta.phone.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 调系统应用开给 HyperOS 系统智能体（AIOS 的 OSBot）的工具服务。这种服务是导出的 Service（action
 * `com.aios.osbot.action.APP_TOOL_PROVIDER`），Binder 接口 `com.aios.apptoolsdk.aidl.IAppToolProvider` 只有一个方法
 * `String handleRequest(String)`（事务号 1），收发 MCP 式的 JSON-RPC：`tools/call` 带 `name` 和 `arguments`，答
 * `result.content[0].text` 和 `result.isError`。有哪些工具写在应用 APK 的 `assets/app_tools.json` 里。
 *
 * 服务只放行系统应用或平台签名的调用方（SDK 的 AppToolProviderService.isCallerSystemSigned，看的是 Binder 调用方的 uid），
 * Zeta 自己不行，system（uid 1000）可以。可是以 system 起的 app_process 在 ActivityManager 里没有应用记录，绑不了服务，
 * 所以分两步：Zeta 用 [bind] 把服务拉起来、拿住绑定；[ProviderHelper] 以 system 身份执行 [op]，经
 * `IActivityManager.peekService` 取到同一个 Binder 发请求（[handle]；peekService 只返回已经绑定过的服务，Intent 要和绑定时的一样）。
 * 结果交回后 Zeta 解绑。
 */
internal object AppToolCall {
    private const val DESCRIPTOR = "com.aios.apptoolsdk.aidl.IAppToolProvider"
    private const val HANDLE_REQUEST = IBinder.FIRST_CALL_TRANSACTION

    /** 等服务连上的上限：服务所在的应用没在跑时要先起进程。 */
    private const val BIND_TIMEOUT_MS = 15_000L

    /** 一次 tools/call 的结果：工具答的文字，以及它是不是出错。 */
    data class Reply(val text: String, val isError: Boolean)

    /** handleRequest 的请求：调 [tool]，参数 [arguments]。 */
    fun request(tool: String, arguments: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", tool)
            put("arguments", arguments)
        }
    }.toString()

    /** 交给 [ProviderHelper] 的一项操作：在服务 [component]（`包名/类名`）上调 [tool]。 */
    fun op(component: String, tool: String, arguments: JsonObject): JsonObject = buildJsonObject {
        put("op", "appTool")
        put("component", component)
        put("request", request(tool, arguments))
    }

    /** [op] 的结果变成 [Reply]；辅助进程失败、调用方没被放行、没有这个工具时抛出异常。 */
    fun reply(result: JsonObject): Reply {
        if (result.bool("ok") != true) throw IllegalStateException(result.str("error") ?: "辅助进程失败")
        return parse(result.str("response") ?: throw IllegalStateException("服务没有回答"))
    }

    /** 解析 handleRequest 的回答。JSON-RPC 层的错误抛异常，工具自己报的错是 [Reply.isError]。 */
    fun parse(response: String): Reply {
        val json = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: throw IllegalStateException("服务的回答不是 JSON：" + response.take(200))
        json.obj("error")?.let { throw IllegalStateException("服务拒绝了请求：" + (it.str("message") ?: it.toString()).take(300)) }
        val result = json.obj("result") ?: throw IllegalStateException("服务的回答里没有 result")
        val text = result.arr("content").orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
        return Reply(text, result.bool("isError") == true)
    }

    // ---- Zeta 这一侧 ----

    /** Zeta 对服务的一次绑定。拿着它的期间，辅助进程 peek 得到服务的 Binder；用完 close 解绑。 */
    class Binding internal constructor(private val context: Context, private val connection: ServiceConnection) : AutoCloseable {
        override fun close() {
            runCatching { context.unbindService(connection) }
        }
    }

    /** 绑定服务 [component]（`包名/类名`），等到连上。会阻塞，在后台线程用。 */
    fun bind(context: Context, component: String): Binding {
        val name = ComponentName.unflattenFromString(component) ?: throw IllegalArgumentException("bad component $component")
        val app = context.applicationContext
        val connected = CountDownLatch(1)
        var refused = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = connected.countDown()
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onBindingDied(name: ComponentName?) {
                refused = true
                connected.countDown()
            }
            override fun onNullBinding(name: ComponentName?) {
                refused = true
                connected.countDown()
            }
        }
        val binding = Binding(app, connection)
        try {
            if (!app.bindService(Intent().setComponent(name), connection, Context.BIND_AUTO_CREATE)) {
                throw IllegalStateException("绑定不了 ${name.packageName} 的工具服务（应用没装、版本不对或服务被停用）")
            }
            if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("${BIND_TIMEOUT_MS / 1000} 秒内没连上 ${name.packageName} 的工具服务")
            }
            if (refused) throw IllegalStateException("${name.packageName} 的工具服务拒绝了绑定")
            return binding
        } catch (e: Exception) {
            binding.close()
            throw e
        }
    }

    // ---- 辅助进程这一侧 ----

    /** [ProviderHelper] 的 appTool 操作：component、request → response。只在以 system 身份起的辅助进程里调用。 */
    fun handle(op: JsonObject): JsonObject {
        val component = op.str("component")?.let(ComponentName::unflattenFromString) ?: throw IllegalArgumentException("no component")
        val request = op.str("request") ?: throw IllegalArgumentException("no request")
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val binder = Class.forName("android.app.IActivityManager")
            .getMethod("peekService", Intent::class.java, String::class.java, String::class.java)
            .invoke(activityManager, Intent().setComponent(component), null, "android") as? IBinder
            ?: throw IllegalStateException("service not bound: " + component.flattenToShortString())
        val data = Parcel.obtain()
        val answer = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(request)
            if (!binder.transact(HANDLE_REQUEST, data, answer, 0)) throw IllegalStateException("transaction not handled")
            answer.readException()
            return buildJsonObject {
                put("ok", true)
                put("response", answer.readString())
            }
        } finally {
            data.recycle()
            answer.recycle()
        }
    }
}
