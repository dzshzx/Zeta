package com.dzshzx.zeta.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.Settings as ZetaSettings
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.ui.MainActivity
import com.dzshzx.zeta.ui.settings.SettingsLink
import com.dzshzx.zeta.ui.settings.SettingsSubPage

/**
 * 系统设置 `enabled_accessibility_services` 的读写规则。纯函数：只追加、只删本包这一项，别的服务原样保留。
 * 列表是冒号分隔的组件名，同一个组件可能写成“包名/完整类名”或“包名/.类名”。
 */
object AccessibilityList {
    /** 系统里的原值规整一下：没设过是 null，`settings get` 读出来是字面的 "null"。 */
    fun normalize(raw: String?): String = raw?.trim()?.takeUnless { it == "null" }.orEmpty()

    fun entries(raw: String?): List<String> =
        normalize(raw).split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** “包名/.类名”展开成“包名/包名.类名”，便于比较。 */
    fun canonical(component: String): String {
        val slash = component.indexOf('/')
        if (slash <= 0) return component
        val pkg = component.substring(0, slash)
        val cls = component.substring(slash + 1)
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else component
    }

    fun contains(raw: String?, component: String): Boolean {
        val target = canonical(component)
        return entries(raw).any { canonical(it) == target }
    }

    /** 在末尾加上 [component]；已经有了就原样返回。原有内容一个字不改。 */
    fun withAdded(raw: String?, component: String): String {
        val current = normalize(raw)
        if (contains(current, component)) return current
        return if (current.isEmpty()) component else current.trimEnd(':') + ":" + component
    }

    /** 去掉 [component]（两种写法都算），别的按原来的顺序保留。 */
    fun withRemoved(raw: String?, component: String): String {
        val target = canonical(component)
        return entries(raw).filterNot { canonical(it) == target }.joinToString(":")
    }

    /** [before] 里有、[after] 里没有的服务（[except] 除外）：用来核对写完没丢别人的。 */
    fun lost(before: String?, after: String?, except: String? = null): List<String> {
        val kept = entries(after).map(::canonical).toSet()
        val skip = except?.let(::canonical)
        return entries(before).filter { canonical(it) != skip && canonical(it) !in kept }
    }
}

/** 守护一次检查后要做的事。 */
enum class GuardAction {
    /** 不用管：开着，或者本来就没开（没开过、用户在 Zeta 里关的、手机操作关着）。 */
    None,

    /** 被关了，但设置里关了自动加回：只记一笔。 */
    Report,

    /** 被关了：经 root 加回。 */
    Restore,

    /** 被关了，可短时间里已经加回好几次又被关掉：不再加，记下并发通知。 */
    GiveUp,
}

/** 守护的判断，纯函数。 */
object GuardPolicy {
    /** 这段时间里最多自动加回几次，再被关就不跟系统拉锯了。 */
    const val WINDOW_MS = 10 * 60 * 1000L
    const val MAX_RESTORES = 3

    /**
     * [listed]：本包的服务在启用列表里；[armed]：之前开着、不是在 Zeta 里被关掉的；
     * [phoneTools]：手机操作开着；[autoRestore]：设置里允许自动加回；[recent]：最近自动加回的时刻。
     */
    fun decide(
        listed: Boolean,
        armed: Boolean,
        phoneTools: Boolean,
        autoRestore: Boolean,
        recent: List<Long>,
        now: Long,
    ): GuardAction =
        when {
            listed || !armed || !phoneTools -> GuardAction.None
            !autoRestore -> GuardAction.Report
            recent.count { now - it in 0 until WINDOW_MS } >= MAX_RESTORES -> GuardAction.GiveUp
            else -> GuardAction.Restore
        }
}

/**
 * 无障碍守护：系统会把 Zeta 的无障碍服务从启用列表里拿掉（应用被强行停止时 AOSP 的
 * `onPackagesForceStoppedLocked`，或者别的设置程序），用户多半不知道，下次让 Claude 操作手机时才失败。
 *
 * 什么时候查：Zeta 启动时（[start]），对话开始用手机工具时，以及系统设置变化时（ContentObserver 盯着
 * `enabled_accessibility_services`）。只守“之前开着”的服务：看到它在列表里就记下（armed）， 在 Zeta
 * 里亲手关掉时不再守（[remove]），直到它下次被打开。手机操作关着时不守。
 *
 * 被关了：有 root 就经 root 只把本包的组件追加回去，别的服务一个不丢；读和写之间列表被别人改过，就重新读再做。 没有 root
 * 时发通知，点开到权限健康页。设置里可以关掉自动加回（[autoRestore]）。每次都记进诊断日志。
 */
object AccessibilityGuard {
    /** Zeta 设置（SharedPreferences `zeta`）里的开关，可以备份。 */
    const val PREF_AUTO_RESTORE = "accessibilityGuard"

    /** 守护自己的状态（这台手机上服务开没开过），单独一个文件，不进设置备份。 */
    private const val STATE = "zeta_guard"
    private const val KEY_ARMED = "armed"

    private const val ATTEMPTS = 5

    private val lock = Any()
    private var app: Context? = null
    private var handler: Handler? = null

    /** 上次检查时服务在不在列表里；null 是还没查过。同一次“被关”只报一次。 */
    private var lastListed: Boolean? = null
    private val restores = ArrayList<Long>()
    private var gaveUp = false

    /** 这次被关以后加回失败过（没有 root 等）：只因设置变化触发的检查不再重试，也不再重复提醒。 */
    private var failed = false

    /** 本包的无障碍服务组件名。正式版和测试版各是各的，启用、停用都只动自己这一项。 */
    val SERVICE: String = AppVariant.current.component(ZetaAccessibilityService::class.java.name)

    /** 无障碍服务是否连着。 */
    val connected: Boolean
        get() = ZetaAccessibilityService.instance != null

    /** 应用启动时调用一次（ConversationHub 创建时）：开始盯系统设置，并马上查一次。 */
    fun start(context: Context) {
        val appContext = context.applicationContext
        val worker =
            synchronized(lock) {
                if (handler != null) return
                app = appContext
                Handler(HandlerThread("zeta-a11y-guard").apply { start() }.looper).also {
                    handler = it
                }
            }
        val observer =
            object : ContentObserver(worker) {
                override fun onChange(selfChange: Boolean) {
                    // 一次改动可能连着来几次通知，合成一次
                    worker.removeCallbacks(settingsChanged)
                    worker.postDelayed(settingsChanged, 300)
                }
            }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer,
            )
        }
            .onFailure { DiagLog.warn(DiagTopic.Accessibility, "盯不了系统的无障碍设置：${it.message}") }
        worker.post { check(appContext, "Zeta 启动") }
    }

    private const val SETTINGS_CHANGED = "系统设置变了"

    private val settingsChanged = Runnable { app?.let { check(it, SETTINGS_CHANGED) } }

    /** 在守护的线程上查一次（对话开始用手机工具时）。 */
    fun checkSoon(reason: String) {
        val context = app ?: return
        handler?.post { check(context, reason) }
    }

    fun autoRestore(context: Context): Boolean = prefs(context).getBoolean(PREF_AUTO_RESTORE, true)

    fun setAutoRestore(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTO_RESTORE, enabled).apply()
        DiagLog.info(DiagTopic.Accessibility, if (enabled) "打开了“被关掉后自动打开”" else "关掉了“被关掉后自动打开”")
        if (enabled) {
            synchronized(lock) { gaveUp = false }
            checkSoon("打开了自动加回")
        }
    }

    /** 守护是否在守这台手机上的服务（之前开着、不是在 Zeta 里关掉的）。 */
    fun armed(context: Context): Boolean = state(context).getBoolean(KEY_ARMED, false)

    /** 读一次、判断、需要时加回。在后台线程调用。 */
    fun check(context: Context, reason: String) {
        synchronized(lock) {
            val raw = read(context)
            val listed = AccessibilityList.contains(raw, SERVICE)
            val was = lastListed
            lastListed = listed
            if (listed) {
                if (!armed(context)) setArmed(context, true)
                gaveUp = false
                failed = false
                cancelNotice(context)
                return
            }
            val now = System.currentTimeMillis()
            restores.removeAll { now - it >= GuardPolicy.WINDOW_MS }
            val action =
                GuardPolicy.decide(
                    false,
                    armed(context),
                    ZetaSettings.of(context).phoneTools,
                    autoRestore(context),
                    restores,
                    now,
                )
            // 同一次被关只报一次：上次已经知道不在列表里的，这次只在要加回时再试
            val fresh = was != false
            when (action) {
                GuardAction.None -> Unit
                GuardAction.Report ->
                    if (fresh)
                        DiagLog.warn(DiagTopic.Accessibility, "无障碍服务被关掉了（$reason），设置里关了自动打开，没有加回")
                GuardAction.GiveUp -> giveUp(context, reason)
                GuardAction.Restore -> restore(context, reason, now)
            }
        }
    }

    /** 短时间里被关掉太多次：不再自动加回，报一次、发通知。持着 [lock] 调用。 */
    private fun giveUp(context: Context, reason: String) {
        if (gaveUp) return
        gaveUp = true
        DiagLog.error(
            DiagTopic.Accessibility,
            "无障碍服务 ${GuardPolicy.WINDOW_MS / 60_000} 分钟里被关掉 ${GuardPolicy.MAX_RESTORES} 次以上，不再自动加回（$reason）",
        )
        notice(context, "无障碍服务被反复关掉", "Zeta 不再自动打开它，点这里查看权限健康")
    }

    /** 把被关掉的服务加回去；加不回时报一次、发通知。持着 [lock] 调用。 */
    private fun restore(context: Context, reason: String, now: Long) {
        if (gaveUp || failed && reason == SETTINGS_CHANGED) return
        if (!failed) DiagLog.warn(DiagTopic.Accessibility, "无障碍服务不在系统的启用列表里了（$reason）")
        when (val result = append(context)) {
            is AppendResult.Added -> {
                restores += now
                failed = false
                DiagLog.info(DiagTopic.Accessibility, "已经 root 把无障碍服务加回启用列表" + result.note())
                cancelNotice(context)
            }
            AppendResult.Present -> lastListed = true
            AppendResult.NoRoot ->
                failOnce {
                    DiagLog.warn(DiagTopic.Accessibility, "没有 root，没法自动加回无障碍服务，已发通知")
                    notice(context, "无障碍服务被关掉了", "Zeta 没有 root，没法自动打开。点这里到权限健康页处理")
                }
            is AppendResult.Failed ->
                failOnce {
                    DiagLog.error(DiagTopic.Accessibility, "加回无障碍服务没有成功：${result.reason}")
                    notice(context, "无障碍服务被关掉了", "Zeta 没能自动打开它。点这里到权限健康页处理")
                }
        }
    }

    /** 加回失败只报第一次：之后同样失败不再报、不再发通知，直到成功一次。 */
    private inline fun failOnce(report: () -> Unit) {
        if (failed) return
        failed = true
        report()
    }

    /** 追加的结果。 */
    sealed interface AppendResult {
        /** 加进去了；[retries] 是因为列表被别人改过而重读的次数。 */
        data class Added(val retries: Int) : AppendResult {
            fun note(): String = if (retries > 0) "（期间列表被别处改过，重读了 $retries 次）" else ""
        }

        data object Present : AppendResult

        data object NoRoot : AppendResult

        data class Failed(val reason: String) : AppendResult
    }

    /** 用 root 把 Zeta 的无障碍服务加进系统的启用列表（[append]），等它连上。返回是否连上了。在后台线程调用。 */
    fun enable(context: Context, why: String = "要用手机工具"): Boolean {
        if (connected) return true
        append(context, why)
        repeat(25) {
            if (connected) return true
            Thread.sleep(200)
        }
        return false
    }

    /**
     * 经 root 把本包的服务追加到启用列表末尾，并打开总开关 `accessibility_enabled`。
     * 比较后写入：脚本里先读当前值，和这边算新值时依据的不一样（中间被别人改过）就不写、把新值报回来， 这边按新值重算再试。写完再读一遍，核对别的服务都还在。在后台线程调用。
     */
    fun append(context: Context, why: String? = null): AppendResult =
        synchronized(lock) {
            var expected = AccessibilityList.normalize(read(context))
            var retries = 0
            repeat(ATTEMPTS) {
                if (AccessibilityList.contains(expected, SERVICE)) return alreadyListed(context)
                val next = AccessibilityList.withAdded(expected, SERVICE)
                val result = runCatching {
                    LinuxEnv.runRoot(
                        LinuxEnv.androidScript(COMPARE_AND_SET, expected, next, "1"),
                        10_000,
                    )
                }
                    .getOrNull()
                val out = result?.stdout.orEmpty().trim()
                when {
                    out == "ok" -> return written(context, expected, why, retries)
                    out.startsWith(CHANGED) -> {
                        expected = AccessibilityList.normalize(out.removePrefix(CHANGED))
                        retries++
                    }
                    result == null || result.exitCode != 0 && !LinuxEnv.hasRoot() ->
                        return AppendResult.NoRoot
                    else ->
                        return AppendResult.Failed(
                            (result.stderr.ifBlank { out }).trim().take(300).ifEmpty {
                                "退出码 ${result.exitCode}"
                            },
                        )
                }
            }
            AppendResult.Failed("列表一直在被别处改动，试了 $ATTEMPTS 次没写进去")
        }

    /** 列表里已经有本包的服务：总开关也要开着，系统才会去绑定。持着 [lock] 调用。 */
    private fun alreadyListed(context: Context): AppendResult {
        runCatching {
            LinuxEnv.runRoot(
                LinuxEnv.androidScript("settings put secure accessibility_enabled 1"),
                10_000,
            )
        }
        setArmed(context, true)
        return AppendResult.Present
    }

    /** 写进去了：再读一遍，核对别的服务都还在（[expected] 是写之前的列表）。持着 [lock] 调用。 */
    private fun written(
        context: Context,
        expected: String,
        why: String?,
        retries: Int,
    ): AppendResult {
        setArmed(context, true)
        val after = read(context)
        val lost = AccessibilityList.lost(expected, after, except = SERVICE)
        if (lost.isNotEmpty()) {
            DiagLog.error(DiagTopic.Accessibility, "写入后别的无障碍服务不见了：${lost.joinToString()}")
        }
        if (why != null) DiagLog.info(DiagTopic.Accessibility, "已经 root 打开无障碍服务（$why）")
        lastListed = true
        return AppendResult.Added(retries)
    }

    /**
     * 经 root 把本包的服务从启用列表里拿掉（Zeta 里的“关闭无障碍服务”，或者重新绑定前先拿掉）。 之后不再守护，直到它下次被打开。别的服务原样保留，同样比较后写入。在后台线程调用。
     */
    fun remove(context: Context?, why: String) =
        synchronized(lock) {
            val target = context ?: app
            if (target != null) setArmed(target, false)
            lastListed = false
            var expected = AccessibilityList.normalize(target?.let(::read))
            repeat(ATTEMPTS) {
                if (!AccessibilityList.contains(expected, SERVICE)) return@synchronized
                val next = AccessibilityList.withRemoved(expected, SERVICE)
                val result = runCatching {
                    LinuxEnv.runRoot(
                        LinuxEnv.androidScript(COMPARE_AND_SET, expected, next, "0"),
                        10_000,
                    )
                }
                    .getOrNull()
                val out = result?.stdout.orEmpty().trim()
                when {
                    out == "ok" -> {
                        DiagLog.info(DiagTopic.Accessibility, "Zeta 关掉了自己的无障碍服务（$why）")
                        return@synchronized
                    }
                    out.startsWith(CHANGED) ->
                        expected = AccessibilityList.normalize(out.removePrefix(CHANGED))
                    else -> {
                        DiagLog.warn(DiagTopic.Accessibility, "关掉无障碍服务没有成功（$why）")
                        return@synchronized
                    }
                }
            }
        }

    private const val CHANGED = "changed:"

    /**
     * $2 算新值时依据的旧值，$3 新值，$4 为 1 时同时打开总开关。读到的和 $2 不一样就不写，报 `changed:<当前值>`。
     * 新值为空时删掉这一项（和系统设置里关光所有服务一样）。
     */
    private const val COMPARE_AND_SET =
        "cur=\$(settings get secure enabled_accessibility_services); [ \"\$cur\" = null ] && cur=; " +
            "if [ \"\$cur\" != \"\$2\" ]; then printf 'changed:%s' \"\$cur\"; exit 3; fi; " +
            "if [ -n \"\$3\" ]; then settings put secure enabled_accessibility_services \"\$3\" || exit 4; " +
            "else settings delete secure enabled_accessibility_services >/dev/null || exit 4; fi; " +
            "if [ \"\$4\" = 1 ]; then settings put secure accessibility_enabled 1; fi; echo ok"

    private fun read(context: Context): String? = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    }
        .getOrNull()

    private fun prefs(context: Context) =
        context.getSharedPreferences(ZetaSettings.FILE, Context.MODE_PRIVATE)

    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    private fun setArmed(context: Context, armed: Boolean) {
        state(context).edit().putBoolean(KEY_ARMED, armed).apply()
    }

    /** 通知点开到权限健康页。 */
    private fun notice(context: Context, title: String, text: String) {
        runCatching {
            val open =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SettingsLink.EXTRA, SettingsSubPage.PermissionHealth.link)
            val pending =
                PendingIntent.getActivity(
                    context,
                    Notifier.ID_ACCESSIBILITY,
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            Notifier.post(context, null, Notifier.ID_ACCESSIBILITY, title, text, pending)
        }
    }

    private fun cancelNotice(context: Context) {
        runCatching { Notifier.cancel(context, null, Notifier.ID_ACCESSIBILITY) }
    }
}
