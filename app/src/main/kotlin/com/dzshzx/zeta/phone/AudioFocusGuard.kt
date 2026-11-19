package com.dzshzx.zeta.phone

import android.content.Context
import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.phone.tools.rootShell
import kotlin.concurrent.thread

/**
 * 后台屏幕上的应用不抢音频焦点：开到后台屏幕上时，用 appops 把它的 TAKE_AUDIO_FOCUS 设成 ignore，
 * 它在那边放声音也拿不到焦点，用户在主屏上听的东西不会被停掉。离开后台屏幕时改回原来的模式：
 * 到了主屏上（show_to_user、open_app 开到主屏、用户自己在主屏上打开它），或者开过它的后台屏幕都关了。
 *
 * - 只改原来是平常模式的：allow，或者 foreground（在前台才给焦点，Android 17 的 HyperOS 上几乎所有应用都是它）。
 *   设成 ignore、deny、default 的，和 uid 级单独设过的不碰；正开在主屏前台（用户正在用）的也不改。
 *   改回时写回读到的那个模式，不一律写 allow。
 * - 改之前先把“包名=原来的模式”记进设置（测试版和正式版的设置各是各的，各还原各的）。Zeta 中途被杀、
 *   或某次没改回去，进程启动和后台屏幕新建、关闭时补上。
 * - 改回之前先看它还是不是 ignore：期间被别人改过、或者应用重装过，就不覆盖，只丢掉记录。
 *
 * 每个进程一份（[of]）。要跑 root 命令，不要在界面线程上调用（[onWindowShown] 除外，它自己另起线程）。
 */
internal class AudioFocusGuard(
    private val shell: (String) -> CommandResult,
    private val store: Store,
    private val user: Int,
) {
    /** 记录存在哪：一项一个“包名=原来的模式”。写完要已经落盘：记录是为了 Zeta 被杀以后还原用的。 */
    interface Store {
        fun load(): Set<String>
        fun save(entries: Set<String>)
    }

    /** `cmd appops get <包名> TAKE_AUDIO_FOCUS` 读出来的：包级模式（没设过、也没用过时是 null）、uid 级模式、这个操作的默认模式。 */
    class Modes(val packageMode: String?, val uidMode: String?, val defaultMode: String?)

    /** 改回时怎么做。 */
    enum class Restore {
        /** 还是 ignore：写回原来的模式。 */
        WRITE,

        /** 已经不是 ignore（被别人改过、重装过）或者应用没了：不写，丢掉记录。 */
        FORGET,

        /** 读不出来（没有 root、超时）：记录留着，下次再补。 */
        LATER,
    }

    private val lock = Any()

    /** 改过的包和原来的模式，和 [store] 里的一致。第一次用时读出来。 */
    private var loaded: MutableMap<String, String>? = null

    /** 每个改过的包是为哪几块后台屏幕改的。记录里有、这里没有的，是上一个进程或者某次没改回去留下的。 */
    private val owners = HashMap<String, MutableSet<Int>>()

    /** 记录里的包名，给 [onWindowShown] 不拿锁先看一眼。 */
    @Volatile private var mutedNow: Set<String> = emptySet()

    private fun records(): MutableMap<String, String> =
        loaded ?: decode(store.load()).toMutableMap().also {
            loaded = it
            mutedNow = it.keys.toSet()
        }

    private fun save(records: MutableMap<String, String>) {
        store.save(encode(records))
        mutedNow = records.keys.toSet()
    }

    /**
     * [packageName] 要开到后台屏幕 [display] 上：先禁掉它的音频焦点。已经禁着就只记下这块屏也要它。
     * [mainForeground] 给出主屏前台的应用，正好是它（用户正在用）就不改。返回禁着没有。
     */
    fun mute(packageName: String, display: Int, mainForeground: () -> String?): Boolean = synchronized(lock) {
        if (!validPackage(packageName)) return false
        val records = records()
        if (packageName !in records) {
            if (mainForeground() == packageName) return false
            val original = originalMode(parse(appops("get", packageName).stdout)) ?: return false
            // 先记下再改：改完马上被杀，下次启动也知道要改回什么
            records[packageName] = original
            save(records)
            if (appops("set", packageName, IGNORE).exitCode != 0) {
                // 没改成也可能其实改了：按留下的记录处理，还是 ignore 就改回去
                restoreLocked(packageName)
                return false
            }
        }
        owners.getOrPut(packageName, ::HashSet) += display
        true
    }

    /** [packageName] 到了主屏上：不管为哪块后台屏幕改的，都改回去。没改过它就什么也不做。 */
    fun movedToMain(packageName: String): Unit = synchronized(lock) {
        if (packageName !in records()) return
        owners.remove(packageName)
        restoreLocked(packageName)
    }

    /** 后台屏幕 [display] 关了：只为它改的改回去，顺带补上留下来的。 */
    fun screenGone(display: Int) = synchronized(lock) {
        owners.values.forEach { it.remove(display) }
        owners.values.removeAll { it.isEmpty() }
        restoreLeftoversLocked()
    }

    /** 补还原：记着改过、但没有哪块活着的后台屏幕要它的。进程启动、后台屏幕新建时调用。 */
    fun restoreLeftovers() = synchronized(lock) { restoreLeftoversLocked() }

    /**
     * 无障碍服务看到 [packageName] 的窗口出现在 [displayId] 上。是主屏（[mainDisplay]）、又是禁着的，就另起线程改回去：
     * 用户自己在主屏上打开了它。可以在界面线程上调用，不是禁着的包不做任何事。
     */
    fun onWindowShown(packageName: String?, displayId: Int, mainDisplay: () -> Int) {
        if (packageName == null || packageName !in mutedNow || displayId != mainDisplay()) return
        thread(name = "zeta-audio-focus", isDaemon = true) { movedToMain(packageName) }
    }

    private fun restoreLeftoversLocked() {
        records().keys.filter { it !in owners }.forEach(::restoreLocked)
    }

    /** 改回原来的模式（记录里有才做）。读不出来、写不成就留着记录，下次再补。 */
    private fun restoreLocked(packageName: String) {
        val records = records()
        val original = records[packageName] ?: return
        when (restoreAction(appops("get", packageName))) {
            Restore.WRITE -> if (appops("set", packageName, original).exitCode != 0) return
            Restore.LATER -> return
            Restore.FORGET -> Unit
        }
        records.remove(packageName)
        save(records)
    }

    private fun appops(verb: String, packageName: String, mode: String? = null): CommandResult = runCatching {
        shell("cmd appops $verb --user $user ${LinuxEnv.quote(packageName)} $OP" + (mode?.let { " $it" } ?: ""))
    }.getOrElse { CommandResult(-1, "", it.toString()) }

    companion object {
        const val OP = "TAKE_AUDIO_FOCUS"
        const val IGNORE = "ignore"

        /** 应用平常的模式：没单独设过时是其中之一（老系统默认 allow，新系统默认 foreground）。只改这两种。 */
        private val USUAL = setOf("allow", "foreground")

        /** 记录里的模式不认得时写回它：宁可放宽，也不让应用从此拿不到焦点。 */
        private const val FALLBACK = "allow"

        private const val ROOT_TIMEOUT_MS = 10_000L

        private val OP_LINE = Regex("^$OP: (\\w+)")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        /**
         * 能写回去的原模式。default（MODE_DEFAULT）不在里面：音频焦点只认 noteOp 给出的 allow，
         * 写回 default 应用就再也拿不到焦点。
         */
        private val RESTORABLE = setOf("allow", "foreground", "deny")

        @Volatile private var shared: AudioFocusGuard? = null

        /** 本进程的那一份。 */
        fun of(context: Context): AudioFocusGuard = shared ?: synchronized(this) {
            shared ?: AudioFocusGuard(
                shell = { rootShell(it, ROOT_TIMEOUT_MS) },
                store = SettingsStore(Settings(context.applicationContext)),
                user = LinuxEnv.androidUser,
            ).also { shared = it }
        }

        /** 已经建好的那一份；还没有时是 null（那就还没禁过谁，进程启动时的补还原会建它）。 */
        val current: AudioFocusGuard? get() = shared

        fun validPackage(packageName: String): Boolean = PACKAGE.matches(packageName)

        /**
         * 解析 `cmd appops get --user <用户> <包名> TAKE_AUDIO_FOCUS`。可能的样子：
         * `No operations.` 加 `Default mode: foreground`（没设过、也没用过）；`TAKE_AUDIO_FOCUS: foreground; time=…`（包级）；
         * 前面多一行 `Uid mode: TAKE_AUDIO_FOCUS: ignore`（uid 级单独设过）。读不懂（出错、空的）返回 null。
         */
        fun parse(output: String): Modes? {
            var packageMode: String? = null
            var uidMode: String? = null
            var defaultMode: String? = null
            var none = false
            for (raw in output.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("Uid mode:") -> OP_LINE.find(line.removePrefix("Uid mode:").trim())?.let { uidMode = it.groupValues[1] }
                    line == "No operations." -> none = true
                    line.startsWith("Default mode:") -> defaultMode = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                    else -> OP_LINE.find(line)?.let { packageMode = it.groupValues[1] }
                }
            }
            return if (packageMode == null && uidMode == null && !none) null else Modes(packageMode, uidMode, defaultMode)
        }

        /** 要不要改、改回时写什么：是平常模式时返回它；单独设过、读不懂、不知道默认模式时返回 null，不改。 */
        fun originalMode(modes: Modes?): String? {
            modes ?: return null
            if (modes.uidMode != null) return null
            val current = modes.packageMode ?: modes.defaultMode ?: return null
            return current.takeIf { it in USUAL }
        }

        /** 改回之前读到 [current]（`appops get` 的结果）时怎么做。 */
        fun restoreAction(current: CommandResult): Restore {
            val modes = parse(current.stdout)
            return when {
                modes != null -> if (modes.packageMode == IGNORE) Restore.WRITE else Restore.FORGET
                // 应用卸载了：它的 appops 也跟着没了
                "No UID for" in current.stderr || "No UID for" in current.stdout -> Restore.FORGET
                else -> Restore.LATER
            }
        }

        /** 设置里的记录读成“包名 → 原来的模式”。包名不对的丢掉；写不回去的模式按 [FALLBACK] 还原。 */
        fun decode(entries: Set<String>): Map<String, String> = entries.mapNotNull { entry ->
            val packageName = entry.substringBefore('=')
            val mode = entry.substringAfter('=', "")
            if (validPackage(packageName)) packageName to (mode.takeIf { it in RESTORABLE } ?: FALLBACK) else null
        }.toMap()

        fun encode(records: Map<String, String>): Set<String> = records.mapTo(LinkedHashSet()) { (packageName, mode) -> "$packageName=$mode" }
    }

    private class SettingsStore(private val settings: Settings) : Store {
        override fun load(): Set<String> = settings.mutedApps
        override fun save(entries: Set<String>) {
            settings.mutedApps = entries
        }
    }
}
