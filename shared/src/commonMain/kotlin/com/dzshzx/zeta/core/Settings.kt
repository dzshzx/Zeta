package com.dzshzx.zeta.core

import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Zeta 自己的设置（存在 [PrefStore] 里，手机上是 SharedPreferences [FILE]）：Linux 环境、默认工作目录、界面与手机工具的各个开关。
 * 新对话的默认模型、思考强度和权限模式不在这里，用 Claude Code 自己的 settings.json。
 *
 * 全进程一份（[shared]，手机上经 app 的 Settings.of(context) 取）。每一项都用 [pref] 声明，底下是一份
 * mutableStateOf：界面读了就跟着变，写了马上生效，哪个线程上读写都行。 备份导入（[restore]）写完存储后各项按存下来的值重读，界面不用另外刷新。
 * 存成清单的几项（常用指令、定时任务、通知触发）不在这里，见 [PrefsListStore]：它们要在锁里读改写，改完还有别的事要做。
 */
class Settings(private val prefs: PrefStore, private val owner: Any) {

    /** 各项按存储重读自己（[restore] 之后）。要在各项之前初始化。 */
    private val reloads = ArrayList<() -> Unit>()

    /**
     * 一项设置：建好时从存储读出来放进 mutableStateOf，写的时候先换掉它再存盘（[commit] 时同步写盘）。 [onChange]
     * 在读出、写入、重读后各调一次，给要把值交给别处的项用。
     */
    private inner class Pref<T>(
        private val read: PrefStore.() -> T,
        private val write: PrefStore.Editor.(T) -> Unit,
        private val commit: Boolean,
        private val onChange: (T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(prefs.read())

        init {
            onChange(state.value)
            reloads += { prefs.read().also { state.value = it }.let(onChange) }
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (value == state.value) return // 同一个值不重写存储、不重跑 onChange，调用处不用先比较
            state.value = value
            val editor = prefs.edit().apply { write(value) }
            if (commit) editor.commit() else editor.apply()
            onChange(value)
        }
    }

    private fun <T> pref(
        read: PrefStore.() -> T,
        write: PrefStore.Editor.(T) -> Unit,
        commit: Boolean = false,
        onChange: (T) -> Unit = {},
    ): ReadWriteProperty<Any?, T> = Pref(read, write, commit, onChange)

    private fun pref(key: String, default: Boolean) =
        pref({ getBoolean(key, default) }, { putBoolean(key, it) })

    private fun pref(key: String, default: Float) =
        pref({ getFloat(key, default) }, { putFloat(key, it) })

    private fun pref(key: String, default: String) =
        pref({ getString(key, null) ?: default }, { putString(key, it) })

    /** 没设过时为 null；写 null 删掉这一项。 */
    private fun optionalString(key: String) =
        pref({ getString(key, null) }, { if (it == null) remove(key) else putString(key, it) })

    private fun stringSet(key: String, commit: Boolean = false) =
        pref({ getStringSet(key, null)?.toSet().orEmpty() }, { putStringSet(key, it) }, commit)

    /** 存成“键=值”字符串集合的表。 */
    private fun keyValues(key: String) =
        pref(
            read = {
                getStringSet(key, null)
                    .orEmpty()
                    .mapNotNull { entry ->
                        val parts =
                            entry.split('=', limit = 2).takeIf { it.size == 2 }
                                ?: return@mapNotNull null
                        parts[0] to parts[1]
                    }
                    .toMap()
            },
            write = { value ->
                putStringSet(key, value.map { "${it.key}=${it.value}" }.toSet())
            },
        )

    /**
     * 共享文件夹（JSON 数组）。改动对之后进入环境的命令生效，已经在跑的对话看不到新加的。 进入环境的脚本要用这份清单，而 LinuxEnv 拿不到 Settings：每次变了都交给
     * [SharedFolders.current]。
     */
    var sharedFolders: List<SharedFolder> by
        pref(
            read = { SharedFolders.decode(getString("sharedFolders", null)) },
            write = { putString("sharedFolders", SharedFolders.encode(it)) },
            onChange = { SharedFolders.current = it },
        )

    /** Linux 环境和默认工作目录；还没识别出环境时为 null。 */
    var env: LinuxEnvConfig? by
        pref(
            read = {
                getString("user", null)?.let { user ->
                    val home = getString("home", "/root")!!
                    LinuxEnvConfig(
                        rootfs = getString("rootfs", LinuxEnv.OWN_ROOTFS)!!,
                        user = user,
                        uid = getInt("uid", 0),
                        gid = getInt("gid", 0),
                        home = home,
                        defaultCwd = getString("cwd", null) ?: home,
                    )
                }
            },
            write = { value ->
                if (value == null) {
                    remove("user")
                } else {
                    putString("rootfs", value.rootfs)
                        .putString("user", value.user)
                        .putInt("uid", value.uid)
                        .putInt("gid", value.gid)
                        .putString("home", value.home)
                        .putString("cwd", value.defaultCwd)
                }
            },
        )

    /**
     * 远程主机（JSON 数组，见 [RemoteHosts]）：经手机 Debian 里的 OpenSSH 或 Zeta 直连，上面的 Claude Code 也能在 Zeta 里用。
     */
    var remoteHosts: List<RemoteHost> by
        pref(
            read = { RemoteHosts.decode(getString("remoteHosts", null)) },
            write = { putString("remoteHosts", RemoteHosts.encode(it)) },
        )

    /**
     * 侧栏正看着哪台主机（[Backends]）：会话列表列它的，新对话默认开在它上面。存的主机已经删掉时当作手机本机
     * （[ConversationHub.backend]）。只在主线程上写（[ConversationHub.selectBackend]）。
     */
    var backend: String by pref("backend", Backends.LOCAL)

    /** 侧栏的会话列表列全部设备的（[ConversationHub.allHosts]），不只列 [backend] 那台。 */
    var paneAll: Boolean by pref("paneAll", false)

    /** 用户给这台手机在 Zeta 里起的名字；null 是没起，用系统设置里的设备名（[ConversationHub.backendName]）。 */
    var localName: String? by optionalString("localName")

    /** 外观：null 跟随系统，true 深色，false 浅色。 */
    var darkTheme: Boolean? by
        pref(
            read = { if (contains("dark")) getBoolean("dark", false) else null },
            write = { if (it == null) remove("dark") else putBoolean("dark", it) },
        )

    /** 把手机工具（读屏、点按、Android 侧命令）交给 Claude。只影响之后新开的 Claude Code 进程；已经开着的对话下次重启进程时生效。 */
    var phoneTools: Boolean by pref("phoneTools", true)

    /** 手机工具当作“主屏”的屏幕编号。平时是 0；调试时可以指到一块虚拟屏上，免得测试打扰用手机的人。界面里没有入口，每次现读。 */
    val phoneDisplay: Int
        get() = prefs.getInt("phoneDisplay", 0)

    /** Claude 打开应用时默认放到后台屏幕上，不占用主屏。立即生效：之后打开的应用放到哪块屏上。键名 backgroundScreen 是历史名字，存盘的键不改。 */
    var agentDisplay: Boolean by pref("backgroundScreen", true)

    /**
     * 为后台运行禁掉了音频焦点、还没恢复的应用（“包名=原来的设置”，见 AudioFocusGuard）。 同步写盘：先记下再改，改完马上被杀，下次启动也知道要改回什么。不要在界面线程上写。
     */
    var mutedApps: Set<String> by stringSet("mutedApps", commit = true)

    /** 回答里的网络图片是否不等点击就加载。 */
    var autoLoadRemoteImages: Boolean by pref("autoLoadRemoteImages", false)

    /** 一轮回答完了自动朗读（见 core/voice/ReadAloud.kt）。 */
    var autoReadAnswers: Boolean by pref("autoReadAnswers", false)

    /** 手机本机上：新对话第一轮结束后请 Claude Code 起一个简短标题，代替第一句话。每次要起标题时读，关掉后还没起的也不再起。 */
    var autoTitle: Boolean by pref("autoTitle", true)

    /** 侧栏里置顶的会话（会话 id）。只是应用这边的标记，会话记录不动。 */
    var pinnedSessions: Set<String> by stringSet("pinnedSessions")

    /** 侧栏的会话列表按哪个时间排（最新回答或发起时间），是这一端自己的设置。 */
    var sessionSort: SessionSort by
        pref(
            read = { SessionSort.of(getString("sessionSort", null)) },
            write = { putString("sessionSort", it.key) },
        )

    /** 外观：界面缩放比例（1 为系统默认大小）。界面读写时按 normalizeInterfaceScale 收到允许的范围里。 */
    var interfaceScale: Float by pref("interfaceScale", 1f)

    /** 对话进行中、Zeta 在前台时保持屏幕常亮。 */
    var keepScreenOn: Boolean by pref("keepScreenOn", true)

    /** 数字助理新开的对话用的模型；null 表示跟随 Claude Code（新对话的默认模型）。只影响数字助理开的对话。 */
    var assistantModel: String? by optionalString("assistantModel")

    /** 数字助理新开的对话用的思考强度；null 表示跟随 Claude Code（按模型存的默认）。只影响数字助理开的对话。 */
    var assistantEffort: String? by optionalString("assistantEffort")

    /** 手机本机上 Zeta 起的对话是否让 Claude Code 自动压缩上下文（见 [AutoCompact]）。起进程时带上，已经在跑的对话下次起进程时生效。 */
    var autoCompact: Boolean by pref("autoCompact", true)

    /** 手机本机上自动压缩的触发点（有效窗口的百分比）；null 用 Claude Code 自己的（快满时）。 */
    var autoCompactPercent: Int? by
        pref(
            read = {
                if (contains("autoCompactPercent"))
                    getInt("autoCompactPercent", 0).takeIf { it in 1..100 }
                else null
            },
            write = {
                if (it == null) remove("autoCompactPercent") else putInt("autoCompactPercent", it)
            },
        )

    /** 远程主机上的自动压缩、压缩时机和自动标题（「主机编号/项=值」），读写经 [claude]；手机本机的是 [autoCompact] 等几项。 */
    var remoteClaude: Map<String, String> by keyValues("remoteClaude")

    /** 主机 [backend] 上 Zeta 起的对话的自动压缩、压缩时机和自动标题。 */
    fun claude(backend: String) = HostClaude(this, backend)

    /** 主机 [hostId] 上新建对话默认用的命令行工具（[Harnesses.CLAUDE] 或 [Harnesses.AGY]）。 */
    fun defaultHarness(hostId: String): String =
        Harnesses.normalize(remoteClaude[HostKeys.key(hostId, HARNESS_ITEM)])

    /** 主机 [hostId] 是否显式记过默认工具。 */
    fun hasDefaultHarness(hostId: String): Boolean =
        HostKeys.key(hostId, HARNESS_ITEM) in remoteClaude

    /** 设置主机 [hostId] 上新建对话默认用的命令行工具。 */
    fun setDefaultHarness(hostId: String, harness: String) {
        remoteClaude =
            remoteClaude + (HostKeys.key(hostId, HARNESS_ITEM) to Harnesses.normalize(harness))
    }

    /** 每天最多自动检查一次新版本：手机正式版在回到前台时（app 的 core/update/AppUpdates.kt，只有正式发版证书签的包才查）。 */
    var autoUpdateCheck: Boolean by pref("autoUpdateCheck", true)

    /** 上次检查更新的时间（毫秒，手动、自动都算）；0 是没查过。自动检查据此一天最多一次。 */
    var updateCheckedAt: Long by
        pref({ getLong("updateCheckedAt", 0L) }, { putLong("updateCheckedAt", it) })

    /** 已经发过通知的新版本（versionCode）：同一个版本只提醒一次；0 是还没发过。 */
    var updateNotified: Int by
        pref({ getInt("updateNotified", 0) }, { putInt("updateNotified", it) })

    /**
     * 测试版调试应用内更新用的覆盖（[com.dzshzx.zeta.core.update.UpdateTrust]）：清单地址、信任的签名证书（SHA-256）。
     * 只由调试工具（tools/vd prefs-set）写，界面里没有入口，每次现读；正式版不读。
     */
    val updateDevManifest: String?
        get() = prefs.getString("updateDevManifest", null)?.takeIf { it.isNotBlank() }

    val updateDevCert: String?
        get() =
            prefs.getString("updateDevCert", null)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** 存下来的全部设置的一份快照（备份导出、导入前比对用）。 */
    fun snapshot(): Map<String, *> = HashMap(prefs.all)

    /** 备份导入：一次写入这些项，值为 null 的删掉；写成了各项按存下来的值重读。写完才返回，成功返回 true。 */
    fun restore(values: Map<String, Any?>): Boolean {
        val editor = prefs.edit()
        for ((key, value) in values) {
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> return false
            }
        }
        if (!editor.commit()) return false
        reloads.forEach { it() }
        return true
    }

    companion object {
        /** 设置文件的名字（shared_prefs/zeta.xml）。别处要往里存东西的（各个 [PrefsListStore]、闹钟登记、无障碍守护）也用它。 */
        const val FILE = "zeta"
        private const val HARNESS_ITEM = "harness"

        @Volatile private var shared: Settings? = null

        /**
         * 本进程的那一份，属于 [owner]（手机上是 Application）：换了 owner 就换一份（单元测试里每个用例是新的 Application）。 第一次要或换了
         * owner 时用 [store] 打开存储。
         */
        fun shared(owner: Any, store: () -> PrefStore): Settings {
            shared
                ?.takeIf { it.owner === owner }
                ?.let {
                    return it
                }
            return synchronized(this) {
                shared?.takeIf { it.owner === owner }
                    ?: Settings(store(), owner).also { shared = it }
            }
        }
    }
}

/**
 * 一台主机（[backend]）上 Zeta 起的对话的自动压缩、压缩时机和自动标题，各台各设各的：手机本机的存在 [Settings.autoCompact] 等几项里， 远程主机的存在
 * [Settings.remoteClaude] 里，没设过的项照手机的。
 */
class HostClaude constructor(private val settings: Settings, private val backend: String) {
    private val local = backend == Backends.LOCAL

    /** 远程设备上设过的值；手机本机的不存在这里，总是 null。 */
    private fun remote(item: String): String? =
        if (local) null else settings.remoteClaude[HostKeys.key(backend, item)]

    private fun store(item: String, value: String) {
        settings.remoteClaude = settings.remoteClaude + (HostKeys.key(backend, item) to value)
    }

    var autoCompact: Boolean
        get() = remote(COMPACT)?.toBooleanStrictOrNull() ?: settings.autoCompact
        set(value) = if (local) settings.autoCompact = value else store(COMPACT, value.toString())

    /** 自动压缩的触发点（有效窗口的百分比）；null 用 Claude Code 自己的（快满时）。 */
    var autoCompactPercent: Int?
        get() =
            when (val stored = remote(PERCENT)) {
                null -> settings.autoCompactPercent
                else -> stored.toIntOrNull()?.takeIf { it in 1..100 }
            }
        set(value) =
            if (local) settings.autoCompactPercent = value
            else store(PERCENT, value?.toString() ?: DEFAULT_POINT)

    /** 起进程时交给 Claude Code 的触发点：关着自动压缩时不带。 */
    val compactPoint: Int?
        get() = autoCompactPercent.takeIf { autoCompact }

    var autoTitle: Boolean
        get() = remote(TITLE)?.toBooleanStrictOrNull() ?: settings.autoTitle
        set(value) = if (local) settings.autoTitle = value else store(TITLE, value.toString())

    private companion object {
        const val COMPACT = "autoCompact"
        const val PERCENT = "autoCompactPercent"
        const val TITLE = "autoTitle"

        /** 压缩时机明确设成 Claude Code 自己的默认点时存的值（没设过的不存，照手机的）。 */
        const val DEFAULT_POINT = "default"
    }
}
