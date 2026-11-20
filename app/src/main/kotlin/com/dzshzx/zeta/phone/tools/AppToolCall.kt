package com.dzshzx.zeta.phone.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.phone.InstalledApps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 调系统应用开给 HyperOS 系统智能体（AIOS 的 OSBot）的工具服务。这种服务是导出的 Service（action [ACTION]），
 * Binder 接口 `com.aios.apptoolsdk.aidl.IAppToolProvider` 只有一个方法 `String handleRequest(String)`（事务号 1），
 * 收发 MCP 式的 JSON-RPC，而且只认 `tools/call`：带 `name` 和 `arguments`，答 `result.content[0].text` 和 `result.isError`。
 * 没有 `tools/list`，有哪些工具只写在应用 APK 的 `assets/app_tools.json` 里（格式见 [AppToolDefs]）。
 *
 * 调用方只说「哪个应用的哪个工具」（[call]），组件由这里按工具清单找（[apps]）。
 *
 * 服务只放行系统应用或平台签名的调用方（SDK 的 AppToolProviderService.isCallerSystemSigned，看的是 Binder 调用方的 uid），
 * Zeta 自己不行，system（uid 1000）可以。可是以 system 起的 app_process 在 ActivityManager 里没有应用记录，绑不了服务，
 * 所以分两步：Zeta 用 [bind] 把服务拉起来、拿住绑定；[ProviderHelper] 以 system 身份执行 [op]，经
 * `IActivityManager.peekService` 取到同一个 Binder 发请求（[handle]；peekService 只返回已经绑定过的服务，Intent 要和绑定时的一样）。
 * 结果交回后 Zeta 解绑。多数应用的服务还要求签名级权限 [BIND_PERMISSION]（小爱定义的），Zeta 拿不到，
 * 由 Zeta 自带的 LSPosed 模块在系统里只对本包放行（xposed/AppToolBindHook.kt）；没装上时绑定被拒。
 */
internal object AppToolCall {
    const val ACTION = "com.aios.osbot.action.APP_TOOL_PROVIDER"

    /** 多数工具服务要求的绑定权限：签名级，由小爱（com.miui.voiceassist）定义。 */
    const val BIND_PERMISSION = "com.aios.osbot.permission.BIND_APP_TOOL"

    private const val ASSET = "assets/app_tools.json"

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

    /**
     * 调应用 [packageName] 的工具 [tool]：找到处理它的服务、绑定、以 system 身份发请求、解析回答。会阻塞，在后台线程用。
     * [then] 是要在同一次辅助进程里紧接着做的操作（比如改完马上读回），结果按顺序交回。
     */
    fun call(
        context: Context,
        packageName: String,
        tool: String,
        arguments: JsonObject,
        timeoutMs: Long = 30_000,
        then: List<JsonObject> = emptyList(),
    ): Pair<Reply, List<JsonObject>> {
        val app = apps(context).firstOrNull { it.packageName == packageName } ?: throw ToolError("$packageName 没有可用的工具服务")
        val definition = app.tools.firstOrNull { it.name == tool } ?: throw ToolError("${app.label}的工具服务里没有 $tool")
        val service = app.serviceFor(definition)
        val results = bind(context, service).use {
            RootProvider.run(context, listOf(op(service, tool, arguments)) + then, timeoutMs, system = true)
        }
        return reply(results.first()) to results.drop(1)
    }

    // ---- 有工具服务的应用 ----

    /** 一个应用读出来的名字和工具清单，按它的版本（lastUpdateTime）缓存；读不到清单的也记一笔空的，不每次重读。 */
    private class Entry(val updated: Long, val label: String, val tools: List<AppToolDefs.Tool>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 这台手机上有工具服务、读得出工具清单的应用，按包名排。 */
    fun apps(context: Context): List<AppToolDefs.App> {
        val services = context.packageManager.queryIntentServices(Intent(ACTION), PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.serviceInfo }
            .groupBy { it.packageName }
        return services.mapNotNull { (pkg, infos) ->
            val info = infos.first().applicationInfo
            val updated = runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime }.getOrDefault(0L)
            val entry = cache[pkg]?.takeIf { it.updated == updated }
                ?: Entry(updated, InstalledApps.label(context, pkg), AppToolDefs.parse(readAsset(info).orEmpty())).also { cache[pkg] = it }
            entry.tools.takeIf { it.isNotEmpty() }?.let { AppToolDefs.App(pkg, entry.label, infos.map { ComponentName(pkg, it.name).flattenToString() }, it) }
        }.sortedBy { it.packageName }
    }

    /** 读过的应用（不碰 PackageManager、不读 APK），给聊天里的标题用。 */
    fun known(): List<AppToolDefs.App> = cache.entries.filter { it.value.tools.isNotEmpty() }
        .map { (pkg, entry) -> AppToolDefs.App(pkg, entry.label, emptyList(), entry.tools) }

    /** 直接从 APK 里读 `assets/app_tools.json`（不为读一个文件建整套 Resources）；先看主 APK，再看拆分包。 */
    private fun readAsset(info: ApplicationInfo): String? =
        (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()).firstNotNullOfOrNull { path ->
            runCatching { ZipFile(path).use { zip -> zip.getEntry(ASSET)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } } } }.getOrNull()
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

    /**
     * 绑定服务 [component]（`包名/类名`），等到连上。会阻塞，在后台线程用。
     * HyperOS 的「关联启动」管控（WakePathChecker）不许 Zeta 绑定没在跑的应用（日志 `MIUILOG-AutoStart, Service/Provider/Broadcast Reject`，
     * bindService 返回 false）；system 不受它管。所以绑不上时先以 system 身份取一下这个应用同一进程里的 Provider，把进程拉起来再绑一次。
     */
    fun bind(context: Context, component: String): Binding {
        val name = ComponentName.unflattenFromString(component) ?: throw IllegalArgumentException("bad component $component")
        tryBind(context.applicationContext, name)?.let { return it }
        val authority = wakeAuthority(context, name)
        val woke = authority?.let { runCatching { RootProvider.one(context, buildJsonObject { put("op", "wake"); put("authority", it) }, system = true) } }
        return tryBind(context.applicationContext, name) ?: throw IllegalStateException(
            "绑定不了 ${name.packageName} 的工具服务（应用没装、版本不对或服务被停用）" + when {
                authority == null -> "，也没找到能拉起它的 Provider"
                woke?.isFailure == true -> "，以 system 拉起它也失败：" + woke.exceptionOrNull()?.message
                else -> ""
            },
        )
    }

    /** 和服务 [name] 在同一个进程里的一个 Provider 的 authority（取它就会拉起这个进程）；没有时是 null。 */
    private fun wakeAuthority(context: Context, name: ComponentName): String? = runCatching {
        val pm = context.packageManager
        val process = pm.getServiceInfo(name, PackageManager.ComponentInfoFlags.of(0)).processName
        val providers = pm.getPackageInfo(name.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong())).providers
        pickAuthority(process, providers.orEmpty().map { it.authority to it.processName })
    }.getOrNull()

    /** [providers]（authority、进程名）里第一个在进程 [process] 里的；一个 Provider 有几个 authority 时用分号隔开，取第一个。 */
    fun pickAuthority(process: String?, providers: List<Pair<String?, String?>>): String? =
        providers.firstOrNull { (authority, owner) -> !authority.isNullOrEmpty() && owner == process }?.first?.substringBefore(';')

    /** 绑一次：bindService 返回 false（关联启动被拦、没有这个服务）时是 null；连上了交回绑定，没连上或被拒绝抛出异常。 */
    private fun tryBind(app: Context, name: ComponentName): Binding? {
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
            val accepted = try {
                app.bindService(Intent().setComponent(name), connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                throw ToolError(
                    "${name.packageName} 的工具服务要系统智能体的绑定权限：在 LSPosed 里启用 Zeta 的模块（作用域：系统框架）并重启手机后才能用（${e.message}）",
                )
            }
            if (!accepted) {
                // 返回 false 也要解绑，系统才会放掉这次的连接记录
                binding.close()
                return null
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
        val binder = ProviderHelper.iActivityManager
            .getMethod("peekService", Intent::class.java, String::class.java, String::class.java)
            .invoke(ProviderHelper.activityManager, Intent().setComponent(component), null, "android") as? IBinder
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
