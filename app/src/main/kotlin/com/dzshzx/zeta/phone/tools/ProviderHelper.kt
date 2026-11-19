package com.dzshzx.zeta.phone.tools

import android.content.AttributionSource
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Point
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/**
 * 读写系统 ContentProvider 和通知的 root 辅助进程。不在 Zeta 的进程里运行：[RootProvider] 经 su 用 app_process 启动它，
 * CLASSPATH 指向 Zeta 自己的 APK，做法同 [com.dzshzx.zeta.phone.VdHelper]。以 root 身份经 ActivityManager 拿到 Provider
 * 的接口直接调用（和系统的 `content` 命令同一条路），所以 Zeta 不用申请日历、短信、通讯录、相册这些权限；
 * 比 `content` 命令多的是：绑定参数、行数上限对 MediaStore 也有效、带类型的结果、插入后拿得到新行的 URI、能取缩略图。
 * 有的系统应用（比如小米录音机）的 Provider 只认系统身份，这时以 system（uid 1000）起它，见 [RootProvider.run]。
 *
 * 参数是一个 JSON 请求：`{"user":0,"ops":[…]}`，每一项按 `op` 执行，结果按顺序放进 `results`，最后打出一行 [MARK] + JSON。
 * - query：uri、projection、selection、args、sort、limit、offset、maxChars → columns、rows、more
 * - batch：authority、ops（type=insert/update/delete，uri、values、refs、selection、args）→ 事务里执行，返回各项的 uri 或 count
 * - image：uri、edge、video → 最长边不超过 edge 的 JPEG（base64）和宽高
 * - notifications：archive（最近移除的条数）、history、since → 当前通知；开了系统通知历史时还有最近移除的和 24 小时内的通知
 *
 * 不写日志、不落盘，结果只经标准输出交回 Zeta。
 */
object ProviderHelper {
    const val MARK = "ZETA_PROVIDER:"

    private const val TEXT_CAP = 8_000
    private const val ROW_CAP = 2_000

    /** 通知历史最多交回这么多条（新的在前）。 */
    private const val HISTORY_CAP = 500

    @JvmStatic
    fun main(args: Array<String>) {
        val reply = try {
            val uid = Process.myUid()
            if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) throw IllegalStateException("needs root, uid=$uid")
            val request = Json.parseToJsonElement(args.getOrNull(0) ?: throw IllegalArgumentException("no request")).jsonObject
            val user = request.int("user") ?: 0
            buildJsonObject {
                put("ok", true)
                putJsonArray("results") {
                    for (op in request.arr("ops").orEmpty()) add(runCatching { run(op.jsonObject, user) }.getOrElse { failure(it) })
                }
            }
        } catch (t: Throwable) {
            failure(t)
        }
        println(MARK + reply)
        System.out.flush()
        exitProcess(0)
    }

    private fun failure(t: Throwable): JsonObject {
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        return buildJsonObject {
            put("ok", false)
            put("error", (cause.javaClass.simpleName + ": " + cause.message.orEmpty()).take(400))
        }
    }

    private fun run(op: JsonObject, user: Int): JsonObject = when (op.str("op")) {
        "query" -> query(op, user)
        "batch" -> batch(op, user)
        "image" -> image(op, user)
        "notifications" -> notifications(op)
        else -> throw IllegalArgumentException("unknown op ${op.str("op")}")
    }

    // ---- Provider ----

    /** 调用方身份：包名要和 uid 对得上（root 是 “root”，system 是 “android”）。 */
    private val attribution: AttributionSource by lazy {
        val uid = Process.myUid()
        AttributionSource.Builder(uid).setPackageName(if (uid == Process.SYSTEM_UID) "android" else "root").build()
    }
    private val activityManager: Any by lazy { Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!! }
    private val iActivityManager: Class<*> by lazy { Class.forName("android.app.IActivityManager") }
    private val iContentProvider: Class<*> by lazy { Class.forName("android.content.IContentProvider") }

    /** 一次 Provider 引用：用完要还，不然系统一直替这个进程留着它。 */
    private class Lease(val authority: String, val user: Int) : AutoCloseable {
        private val token: IBinder = Binder()
        val provider: Any

        init {
            val holder = iActivityManager.getMethod(
                "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
            ).invoke(activityManager, authority, user, token, "Zeta") ?: throw IllegalStateException("no provider $authority")
            provider = holder.javaClass.getField("provider").get(holder) ?: throw IllegalStateException("provider $authority not ready")
        }

        /** 调 IContentProvider 上第一个参数是 AttributionSource、参数个数对得上的那个方法。 */
        fun call(name: String, vararg arguments: Any?): Any? {
            val method = iContentProvider.methods.firstOrNull {
                it.name == name && it.parameterCount == arguments.size + 1 && it.parameterTypes[0] == AttributionSource::class.java
            } ?: throw IllegalStateException("IContentProvider.$name not found")
            return method.invoke(provider, attribution, *arguments)
        }

        override fun close() {
            runCatching {
                iActivityManager.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(activityManager, authority, token, user)
            }
        }
    }

    private fun authorityOf(uri: Uri): String = uri.authority ?: throw IllegalArgumentException("uri without authority")

    private fun query(op: JsonObject, user: Int): JsonObject {
        val uri = Uri.parse(op.str("uri") ?: throw IllegalArgumentException("no uri"))
        val projection = op.arr("projection")?.map { (it as JsonPrimitive).content }?.toTypedArray()
        val limit = (op.int("limit") ?: 50).coerceIn(1, ROW_CAP)
        val offset = (op.int("offset") ?: 0).coerceAtLeast(0)
        val maxChars = (op.int("maxChars") ?: TEXT_CAP).coerceIn(16, TEXT_CAP)
        val queryArgs = Bundle().apply {
            op.str("selection")?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            op.arr("args")?.let { args -> putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.map { (it as JsonPrimitive).content }.toTypedArray()) }
            op.str("sort")?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
            // 多要一行，好知道后面还有没有
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            if (offset > 0) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        Lease(authorityOf(uri), user).use { lease ->
            val cursor = lease.call("query", uri, projection, queryArgs, null) as? Cursor ?: throw IllegalStateException("provider returned no cursor")
            cursor.use { c ->
                // 老式的 Provider 不认 limit/offset（MediaStore 认），没认的在这里自己跳过、截断
                val honored = c.extras?.getStringArray(ContentResolver.EXTRA_HONORED_ARGS).orEmpty().toSet()
                if (offset > 0 && ContentResolver.QUERY_ARG_OFFSET !in honored && !c.moveToPosition(offset - 1)) {
                    return buildJsonObject { put("ok", true); columns(c); putJsonArray("rows") {}; put("more", false) }
                }
                var more = false
                return buildJsonObject {
                    put("ok", true)
                    columns(c)
                    putJsonArray("rows") {
                        var count = 0
                        while (c.moveToNext()) {
                            if (count == limit) {
                                more = true
                                break
                            }
                            add(row(c, maxChars))
                            count++
                        }
                    }
                    put("more", more)
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.columns(c: Cursor) {
        putJsonArray("columns") { c.columnNames.forEach { add(JsonPrimitive(it)) } }
    }

    private fun row(c: Cursor, maxChars: Int): JsonArray = buildJsonArray {
        for (i in 0 until c.columnCount) {
            add(
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> JsonNull
                    else -> JsonPrimitive(c.getString(i)?.take(maxChars))
                },
            )
        }
    }

    private fun values(json: JsonObject?): ContentValues = ContentValues().apply {
        json?.forEach { (key, value) ->
            val p = value as? JsonPrimitive
            when {
                p == null || p is JsonNull -> putNull(key)
                p.isString -> put(key, p.content)
                p.booleanOrNull != null -> put(key, if (p.booleanOrNull == true) 1 else 0)
                p.longOrNull != null -> put(key, p.longOrNull)
                p.doubleOrNull != null -> put(key, p.doubleOrNull)
                else -> put(key, p.contentOrNull)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun batch(op: JsonObject, user: Int): JsonObject {
        val authority = op.str("authority") ?: throw IllegalArgumentException("no authority")
        val operations = ArrayList<ContentProviderOperation>()
        for (element in op.arr("ops").orEmpty()) {
            val item = element.jsonObject
            val uri = Uri.parse(item.str("uri") ?: throw IllegalArgumentException("no uri"))
            if (uri.authority != authority) throw IllegalArgumentException("uri outside $authority")
            val builder = when (item.str("type")) {
                "insert" -> ContentProviderOperation.newInsert(uri)
                "update" -> ContentProviderOperation.newUpdate(uri)
                "delete" -> ContentProviderOperation.newDelete(uri)
                else -> throw IllegalArgumentException("unknown type ${item.str("type")}")
            }
            if (item.str("type") != "delete") builder.withValues(values(item.obj("values")))
            item.obj("refs")?.forEach { (key, index) -> builder.withValueBackReference(key, (index as JsonPrimitive).content.toInt()) }
            item.str("selection")?.let { selection ->
                builder.withSelection(selection, item.arr("args")?.map { (it as JsonPrimitive).content }?.toTypedArray())
            }
            item.int("expect")?.let { builder.withExpectedCount(it) }
            operations += builder.build()
        }
        Lease(authority, user).use { lease ->
            val results = lease.call("applyBatch", authority, operations) as Array<ContentProviderResult>
            return buildJsonObject {
                put("ok", true)
                putJsonArray("results") {
                    for (result in results) addJsonObject {
                        result.uri?.let { put("uri", it.toString()) }
                        result.count?.let { put("count", it) }
                    }
                }
            }
        }
    }

    // ---- 图片 ----

    private fun image(op: JsonObject, user: Int): JsonObject {
        val uri = Uri.parse(op.str("uri") ?: throw IllegalArgumentException("no uri"))
        val edge = (op.int("edge") ?: 1024).coerceIn(64, 2048)
        Lease(authorityOf(uri), user).use { lease ->
            // 视频取系统生成的预览帧；图片读原图再缩，系统缩略图太小，截图里的字看不清
            val file: AssetFileDescriptor = if (op.bool("video") == true) {
                val options = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(edge, edge)) }
                lease.call("openTypedAssetFile", uri, "image/*", options, null) as? AssetFileDescriptor
                    ?: throw IllegalStateException("no preview")
            } else {
                val fd = lease.call("openFile", uri, "r", null) as? ParcelFileDescriptor ?: throw IllegalStateException("cannot open")
                AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            var source = 0 to 0
            val bitmap = file.use {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.util.concurrent.Callable { file })) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    source = info.size.width to info.size.height
                    val (w, h) = PersonalQueries.fitWithin(info.size.width, info.size.height, edge)
                    decoder.setTargetSize(w, h)
                }
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return buildJsonObject {
                put("ok", true)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put("sourceWidth", source.first)
                put("sourceHeight", source.second)
                put("jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        }
    }

    // ---- 通知 ----

    private fun notifications(op: JsonObject): JsonObject {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, Context.NOTIFICATION_SERVICE) as IBinder
        val manager = Class.forName("android.app.INotificationManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val api = Class.forName("android.app.INotificationManager")
        val maxChars = (op.int("maxChars") ?: 2_000).coerceIn(16, TEXT_CAP)
        val since = (op["since"] as? JsonPrimitive)?.longOrNull ?: 0
        val active = api.getMethod("getActiveNotificationsWithAttribution", String::class.java, String::class.java)
            .invoke(manager, "root", null) as? Array<*>
        // 下面两样只在用户开了系统的通知历史（notification_history_enabled）时才有内容：
        // 系统留着的最近移除的通知（划掉的、应用自己撤掉的），以及 24 小时内响铃、弹出过的通知的标题和正文
        val archive = (op.int("archive") ?: 0).coerceIn(0, 500)
        val removed = if (archive == 0) null else runCatching {
            api.getMethod(
                "getHistoricalNotificationsWithAttribution", String::class.java, String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).invoke(manager, "root", null, archive, false) as? Array<*>
        }.getOrNull()
        val history = if (op.bool("history") != true) null else runCatching {
            val result = api.getMethod("getNotificationHistory", String::class.java, String::class.java).invoke(manager, "root", null)
            result?.javaClass?.getMethod("getNotificationsToWrite")?.invoke(result) as? List<*>
        }.getOrNull()
        return buildJsonObject {
            put("ok", true)
            putJsonArray("items") {
                active.orEmpty().filterIsInstance<StatusBarNotification>().forEach { add(notification(it, "active", maxChars)) }
                removed.orEmpty().filterIsInstance<StatusBarNotification>().filter { it.postTime >= since }.forEach { add(notification(it, "removed", maxChars)) }
                history.orEmpty().filterNotNull().mapNotNull { historical(it, since, maxChars) }.sortedByDescending { (it["time"] as JsonPrimitive).long }
                    .take(HISTORY_CAP).forEach { add(it) }
            }
        }
    }

    /** NotificationHistory.HistoricalNotification（隐藏类，经反射读）：只有包名、时间、标题、正文。 */
    private fun historical(item: Any, since: Long, maxChars: Int): JsonObject? {
        fun get(name: String): Any? = runCatching { item.javaClass.getMethod(name).invoke(item) }.getOrNull()
        val time = get("getPostedTimeMs") as? Long ?: return null
        if (time < since) return null
        return buildJsonObject {
            put("pkg", get("getPackage")?.toString().orEmpty())
            put("time", time)
            put("state", "history")
            get("getTitle")?.toString()?.let { put("title", it.take(maxChars)) }
            get("getText")?.toString()?.let { put("text", it.take(maxChars)) }
        }
    }

    private fun notification(sbn: StatusBarNotification, state: String, maxChars: Int): JsonObject {
        val n = sbn.notification
        val extras = n.extras
        fun text(key: String) = extras.getCharSequence(key)?.toString()?.take(maxChars)
        val lines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val messages = runCatching {
            extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)?.mapNotNull { message ->
                val bundle = message as? Bundle ?: return@mapNotNull null
                val body = bundle.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = bundle.getCharSequence("sender")?.toString()
                if (sender.isNullOrBlank()) body else "$sender: $body"
            }?.takeLast(5)?.joinToString("\n")
        }.getOrNull()
        return buildJsonObject {
            put("key", sbn.key)
            put("pkg", sbn.packageName)
            put("time", sbn.postTime)
            put("state", state)
            put("ongoing", n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            put("summary", n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
            text(android.app.Notification.EXTRA_TITLE)?.let { put("title", it) }
            text(android.app.Notification.EXTRA_TEXT)?.let { put("text", it) }
            text(android.app.Notification.EXTRA_BIG_TEXT)?.let { put("big", it) }
            text(android.app.Notification.EXTRA_SUB_TEXT)?.let { put("sub", it) }
            (messages ?: lines)?.let { put("lines", it.take(maxChars)) }
        }
    }
}

/**
 * Zeta 这一侧：经 su 起一次 [ProviderHelper]，交给它一组操作，等它把结果交回来。每次约一秒（启动 app_process）。
 * 结果只在内存里交给调用方，不写日志、不缓存。
 */
internal object RootProvider {
    /**
     * 执行 [ops]，返回与之一一对应的结果；整个调用失败时抛出异常，message 是给人看的原因。
     * [system]：以 system（uid 1000）身份起辅助进程。小米录音机这类系统应用的 Provider 只认系统身份的调用方，
     * 对 root 一律返回空。uid 要在进程启动前换好（Binder 在 app_process 起来时就按当时的身份打开了），用 BusyBox nsenter 的 -S、-G。
     */
    fun run(context: Context, ops: List<JsonObject>, timeoutMs: Long = 30_000, system: Boolean = false): List<JsonObject> {
        val request = buildJsonObject {
            put("user", Process.myUid() / 100_000)
            put("ops", JsonArray(ops))
        }.toString()
        val launch = if (system) "exec \"\$1\" nsenter -t 1 -m -S ${Process.SYSTEM_UID} -G ${Process.SYSTEM_UID} -- app_process" else "exec app_process"
        // app_process 要和系统一样的启动类路径；经 su 进来时环境变量不一定带着，从 system_server 那里抄一份
        val script = "for kv in \$(\"\$1\" tr '\\000' ' ' < /proc/\$(pidof system_server)/environ); do " +
            "case \"\$kv\" in BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|SYSTEMSERVERCLASSPATH=*|ANDROID_*=*) export \"\$kv\";; esac; done; " +
            "CLASSPATH=\"\$2\" $launch /system/bin ${ProviderHelper::class.java.name} \"\$3\""
        val result = LinuxEnv.runRoot(LinuxEnv.androidScript(script, context.applicationInfo.sourceDir, request), timeoutMs)
        if (result.exitCode == -1 && result.stderr == "timeout") throw IllegalStateException("读取超时（${timeoutMs / 1000} 秒）")
        val line = result.stdout.lineSequence().lastOrNull { it.startsWith(ProviderHelper.MARK) }
            ?: throw IllegalStateException("root 辅助进程没有结果（退出码 ${result.exitCode}）" + result.stderr.trim().take(200).let { if (it.isEmpty()) "" else "：$it" })
        val reply = Json.parseToJsonElement(line.removePrefix(ProviderHelper.MARK)).jsonObject
        if (reply.bool("ok") != true) throw IllegalStateException(reply.str("error") ?: "root 辅助进程失败")
        return reply.arr("results").orEmpty().map { it.jsonObject }
    }

    /** 只执行一项，失败时抛出异常。 */
    fun one(context: Context, op: JsonObject, timeoutMs: Long = 30_000, system: Boolean = false): JsonObject {
        val result = run(context, listOf(op), timeoutMs, system).single()
        if (result.bool("ok") != true) throw IllegalStateException(result.str("error") ?: "失败")
        return result
    }

    /** 把 query 的结果变成按列名取值的行。 */
    fun rows(result: JsonObject): List<Map<String, JsonElement>> {
        val columns = result.arr("columns").orEmpty().map { (it as JsonPrimitive).content }
        return result.arr("rows").orEmpty().map { row -> columns.zip(row as JsonArray).toMap() }
    }
}
