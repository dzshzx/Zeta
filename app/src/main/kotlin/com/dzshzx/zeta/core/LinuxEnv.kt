package com.dzshzx.zeta.core

import kotlin.concurrent.thread
import android.content.Context
import java.util.concurrent.TimeUnit

/** Debian 环境里，Claude Code 所属的那个 Linux 用户。 */
data class LinuxEnvConfig(
    val rootfs: String,
    val user: String,
    val uid: Int,
    val gid: Int,
    val home: String,
    val defaultCwd: String,
)

class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * 进入 Debian 环境的方式照 Eta：`su -c` → 管理器自带的 BusyBox → 私有挂载命名空间 → chroot。
 * 与 Eta 不同的两点：先 `nsenter -t 1 -m` 回到全局挂载命名空间（本应用看不到 Eta 的私有数据目录），
 * 进去以后用 setpriv 降到普通用户，Claude Code 的登录状态和配置都在那个用户的家目录里。
 */
object LinuxEnv {
    /** Eta 装的 Debian。在 Eta 的应用数据里，卸载 Eta 或清除它的数据就没了。 */
    const val ETA_ROOTFS = "/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs"

    /**
     * Zeta 自己装的 Debian 放在这里。特意不放进应用数据目录：那里的东西会随卸载、清除数据一起消失，
     * 而这个环境里有 Claude Code 的全部会话和登录状态。
     */
    const val OWN_BASE = "/data/local/zeta/debian"
    const val OWN_ROOTFS = "$OWN_BASE/rootfs"

    /** 没指定时依次找这几个位置。 */
    val KNOWN_ROOTFS = listOf(OWN_ROOTFS, ETA_ROOTFS)

    /** 以 root 身份进入 [rootfs]（装环境时用，这时还没有普通用户）。 */
    fun rootConfig(rootfs: String) = LinuxEnvConfig(rootfs, "root", 0, 0, "/root", "/")

    private const val FIND_BUSYBOX = "BB=; for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do " +
        "[ -x \"\$b\" ] && BB=\$b && break; done; [ -n \"\$BB\" ] || { echo 'zeta: busybox not found' >&2; exit 126; }; "

    // 参数：$1 BusyBox，$2 rootfs，$3 要执行的命令，$4 家目录，$5 用户名，$6 uid，$7 gid，$8 时区，
    // $9 共享文件夹（每行“名字<Tab>Android 侧目录”，见 SharedFolders.mountSpec）。
    // 环境变量 ZETA_NAME 告诉 Debian 里的脚本 Zeta 自己的目录叫什么（正式版 zeta，测试版 zeta-dev，见 AppVariant）。
    private val MOUNT_AND_CHROOT = """
        BB=${'$'}1; R=${'$'}2
        [ -x "${'$'}R/bin/sh" ] || { echo "zeta: rootfs not found: ${'$'}R" >&2; exit 125; }
        "${'$'}BB" mount -o bind "${'$'}R" "${'$'}R" && "${'$'}BB" mount -o remount,bind,suid "${'$'}R" "${'$'}R"
        "${'$'}BB" mount -t proc proc "${'$'}R/proc" || exit 125
        "${'$'}BB" mount -o rbind /dev "${'$'}R/dev" || exit 125
        "${'$'}BB" mount -o rbind /sys "${'$'}R/sys" 2>/dev/null
        [ -d /storage/emulated/0 ] && "${'$'}BB" mount -o bind /storage/emulated/0 "${'$'}R/storage/emulated/0" 2>/dev/null
        "${'$'}BB" mkdir -p "${'$'}R/data/local/tmp" /data/local/tmp/eta "${'$'}R/workspace"
        "${'$'}BB" mount -o bind /data/local/tmp "${'$'}R/data/local/tmp"
        "${'$'}BB" mount -o bind /data/local/tmp/eta "${'$'}R/workspace"
        # 共享文件夹：跟上面一样只绑在这个命名空间里，命令结束就没了；源目录不在的跳过，不影响进入环境
        [ -n "${'$'}9" ] && printf '%s\n' "${'$'}9" | while IFS="${'$'}(printf '\t')" read -r n s; do
          [ -n "${'$'}n" ] && [ -d "${'$'}s" ] && "${'$'}BB" mkdir -p "${'$'}R${SharedFolders.LINUX_ROOT}/${'$'}n" &&
            "${'$'}BB" mount -o bind "${'$'}s" "${'$'}R${SharedFolders.LINUX_ROOT}/${'$'}n"
        done 2>/dev/null
        exec "${'$'}BB" chroot "${'$'}R" /usr/bin/env -i HOME="${'$'}4" USER="${'$'}5" LOGNAME="${'$'}5" SHELL=/bin/bash \
          LANG=C.UTF-8 TERM=dumb TZ="${'$'}8" ${AppVariant.LINUX_NAME_ENV}=${quote(AppVariant.current.linuxName)} /usr/bin/setpriv --reuid="${'$'}6" --regid="${'$'}7" --init-groups /bin/bash -lc "${'$'}3"
    """.trimIndent()

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 给 `su -c` 的整段脚本：在 Debian 里以 [config] 的用户身份、登录 shell 的环境执行 [command]。 */
    fun enterScript(config: LinuxEnvConfig, command: String): String =
        FIND_BUSYBOX + "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" unshare -m --propagation private \"\$BB\" sh -c " +
            quote(MOUNT_AND_CHROOT) + " zeta \"\$BB\" " + quote(config.rootfs) + " " + quote(command) + " " +
            quote(config.home) + " " + quote(config.user) + " " + config.uid + " " + config.gid + " " +
            // 手机的时区：Debian 那边的系统时区多半是 UTC，不传的话 Claude Code 看到的日期时间会差几个小时
            quote(java.util.TimeZone.getDefault().id) + " " + quote(SharedFolders.mountSpec())

    fun start(config: LinuxEnvConfig, command: String): Process =
        ProcessBuilder("su", "-c", enterScript(config, command)).start()

    /**
     * 给 `su -c` 的整段脚本：在 Android 一侧（全局挂载命名空间）用管理器的 BusyBox 执行 [script]。
     * 脚本收到的 $1 是 BusyBox 的路径，后面是 [arguments]。
     */
    fun androidScript(script: String, vararg arguments: String): String =
        FIND_BUSYBOX + "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" sh -c " + quote(script) + " zeta-setup \"\$BB\"" +
            arguments.joinToString("") { " " + quote(it) }

    /**
     * 结束一个经 su 启动的进程，连同它派生的子进程。
     * 这些进程以 root 或 Debian 里的用户身份运行，应用自己发的信号到不了它们，[Process.destroy] 只是关掉管道，所以要再经 root 去杀。
     */
    fun kill(process: Process) {
        val pid = Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
        val alive = runCatching { process.isAlive }.getOrDefault(true)
        process.destroy()
        if (pid == null || !alive) return
        thread(isDaemon = true) {
            runCatching {
                // 这条命令自己超时就算了，不再去杀它（否则会没完没了）
                capture(
                    ProcessBuilder("su", "-c", FIND_BUSYBOX + "kt() { for c in \$(\"\$BB\" pgrep -P \"\$1\"); do kt \"\$c\"; done; kill -9 \"\$1\" 2>/dev/null; }; kt $pid").start(),
                    null,
                    5_000,
                    killOnTimeout = false,
                )
            }
        }
    }

    fun startRoot(script: String): Process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()

    /** 以 root 在 Android 侧直接执行一段脚本（不进 Debian）。[stdin] 不进命令行，别的进程从进程列表里看不到。 */
    fun runRoot(script: String, timeoutMs: Long = 15_000, stdin: String? = null): CommandResult =
        capture(ProcessBuilder("su", "-c", script).start(), stdin, timeoutMs)

    fun run(config: LinuxEnvConfig, command: String, stdin: String? = null, timeoutMs: Long = 30_000): CommandResult =
        capture(start(config, command), stdin, timeoutMs)

    private fun capture(process: Process, stdin: String?, timeoutMs: Long, killOnTimeout: Boolean = true): CommandResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } }.apply { start() }
        val t2 = Thread { runCatching { err.append(process.errorStream.bufferedReader().readText()) } }.apply { start() }
        runCatching {
            process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            if (killOnTimeout) kill(process) else process.destroy()
            return CommandResult(-1, out.toString(), "timeout")
        }
        t1.join(2000)
        t2.join(2000)
        return CommandResult(process.exitValue(), out.toString(), err.toString())
    }

    /** 能否拿到 root。没授权时 `su` 会直接失败或不存在。 */
    fun hasRoot(): Boolean = runCatching { runRoot("id -u", 30_000).let { it.exitCode == 0 && it.stdout.trim() == "0" } }.getOrDefault(false)

    /** [rootfs] 那里是不是真有一个系统。 */
    fun exists(rootfs: String): Boolean = runCatching {
        runRoot(FIND_BUSYBOX + "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" test -x " + quote("$rootfs/bin/bash")).exitCode == 0
    }.getOrDefault(false)

    /**
     * 从 rootfs 的 passwd 里找出装了 Claude Code 的普通用户（家目录下有 .claude），找不到就取第一个普通用户。
     * 返回 null 表示 rootfs 不存在或里面没有普通用户。
     */
    fun detect(rootfs: String): LinuxEnvConfig? {
        val script = FIND_BUSYBOX + "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" sh -c " + quote(
            "R=\$1; [ -r \"\$R/etc/passwd\" ] || exit 3; " +
                "while IFS=: read -r n p u g c h s; do [ \"\$u\" -ge 1000 ] 2>/dev/null && [ \"\$u\" -lt 60000 ] && " +
                "{ [ -d \"\$R\$h/.claude\" ] && echo \"C:\$n:\$u:\$g:\$h\" || echo \"U:\$n:\$u:\$g:\$h\"; }; done < \"\$R/etc/passwd\"; exit 0",
        ) + " zeta " + quote(rootfs)
        val result = runCatching { runRoot(script) }.getOrNull() ?: return null
        if (result.exitCode != 0) return null
        val lines = result.stdout.lines().filter { it.length > 2 }
        val line = lines.firstOrNull { it.startsWith("C:") } ?: lines.firstOrNull() ?: return null
        val f = line.split(":")
        if (f.size < 5) return null
        val uid = f[2].toIntOrNull() ?: return null
        val gid = f[3].toIntOrNull() ?: return null
        return LinuxEnvConfig(rootfs, f[1], uid, gid, f[4], "/workspace")
    }
}

/** 应用设置：Linux 环境、默认工作目录、默认模型。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("zeta", Context.MODE_PRIVATE)

    // 进入环境的脚本要用共享文件夹清单，而 LinuxEnv 拿不到 Settings：一创建就把它交过去
    init {
        SharedFolders.current = sharedFolders
    }

    /** 共享文件夹（JSON 数组）。改动对之后进入环境的命令生效，已经在跑的对话看不到新加的。 */
    var sharedFolders: List<SharedFolder>
        get() = SharedFolders.decode(prefs.getString("sharedFolders", null))
        set(value) {
            prefs.edit().putString("sharedFolders", SharedFolders.encode(value)).apply()
            SharedFolders.current = value
        }

    var env: LinuxEnvConfig?
        get() {
            val user = prefs.getString("user", null) ?: return null
            return LinuxEnvConfig(
                rootfs = prefs.getString("rootfs", LinuxEnv.OWN_ROOTFS)!!,
                user = user,
                uid = prefs.getInt("uid", 0),
                gid = prefs.getInt("gid", 0),
                home = prefs.getString("home", "/root")!!,
                defaultCwd = prefs.getString("cwd", "/workspace")!!,
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove("user")
                } else {
                    putString("rootfs", value.rootfs).putString("user", value.user).putInt("uid", value.uid)
                        .putInt("gid", value.gid).putString("home", value.home).putString("cwd", value.defaultCwd)
                }
            }.apply()
        }

    /** 新会话使用的模型；null 表示沿用 Claude Code 自己的默认值。 */
    var model: String?
        get() = prefs.getString("model", null)
        set(value) = prefs.edit().putString("model", value).apply()

    /** 斜杠命令清单的缓存（JSON 数组）。 */
    var commands: String?
        get() = prefs.getString("commands", null)
        set(value) = prefs.edit().putString("commands", value).apply()

    /** 外观：null 跟随系统。 */
    var darkTheme: Boolean?
        get() = if (prefs.contains("dark")) prefs.getBoolean("dark", false) else null
        set(value) = prefs.edit().apply { if (value == null) remove("dark") else putBoolean("dark", value) }.apply()

    /** 把手机工具（读屏、点按、Android 侧命令）交给 Claude。 */
    var phoneTools: Boolean
        get() = prefs.getBoolean("phoneTools", true)
        set(value) = prefs.edit().putBoolean("phoneTools", value).apply()

    /** 手机工具当作“主屏”的屏幕编号。平时是 0；调试时可以指到一块虚拟屏上，免得测试打扰用手机的人。界面里没有入口。 */
    val phoneDisplay: Int get() = prefs.getInt("phoneDisplay", 0)

    /** Claude 打开应用时默认放到后台屏幕上，不占用主屏。 */
    var backgroundScreen: Boolean
        get() = prefs.getBoolean("backgroundScreen", true)
        set(value) = prefs.edit().putBoolean("backgroundScreen", value).apply()

    /** 为后台运行禁掉了音频焦点、还没恢复的应用（“包名=原来的设置”）。 */
    var mutedApps: Set<String>
        get() = prefs.getStringSet("mutedApps", null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("mutedApps", value).apply()

    /** 回答里的网络图片是否不等点击就加载。 */
    var autoLoadRemoteImages: Boolean
        get() = prefs.getBoolean("autoLoadRemoteImages", false)
        set(value) = prefs.edit().putBoolean("autoLoadRemoteImages", value).apply()

    /** 新对话第一轮结束后请 Claude Code 起一个简短标题，代替第一句话。 */
    var autoTitle: Boolean
        get() = prefs.getBoolean("autoTitle", true)
        set(value) = prefs.edit().putBoolean("autoTitle", value).apply()

    /** 新会话的权限模式；null 表示沿用 Claude Code 设置里的默认值。 */
    var permissionMode: String?
        get() = prefs.getString("permissionMode", null)
        set(value) = prefs.edit().putString("permissionMode", value).apply()

    /** 侧栏里置顶的会话（会话 id）。只是应用这边的标记，会话记录不动。 */
    var pinnedSessions: Set<String>
        get() = prefs.getStringSet("pinnedSessions", null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("pinnedSessions", value).apply()

    /** 外观：强调色。coui 是 COUI 默认蓝，monet 跟随壁纸取色，其余是预设色的名字。 */
    var accent: String
        get() = prefs.getString("accent", null) ?: "coui"
        set(value) = prefs.edit().putString("accent", value).apply()

    /** 外观：深色模式下用纯黑背景。 */
    var pureBlack: Boolean
        get() = prefs.getBoolean("pureBlack", false)
        set(value) = prefs.edit().putBoolean("pureBlack", value).apply()

    /** 外观：界面缩放比例（1 为系统默认大小）。 */
    var interfaceScale: Float
        get() = prefs.getFloat("interfaceScale", 1f)
        set(value) = prefs.edit().putFloat("interfaceScale", value).apply()

    /** 对话进行中、Zeta 在前台时保持屏幕常亮。 */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(value) = prefs.edit().putBoolean("keepScreenOn", value).apply()

    /** 新会话的思考强度（Claude Code 的 --effort）；null 表示沿用 Claude Code 设置里的默认值。 */
    var effort: String?
        get() = prefs.getString("effort", null)
        set(value) = prefs.edit().putString("effort", value).apply()

    /** Zeta 起的对话是否让 Claude Code 自动压缩上下文（见 [AutoCompact]）。 */
    var autoCompact: Boolean
        get() = prefs.getBoolean("autoCompact", true)
        set(value) = prefs.edit().putBoolean("autoCompact", value).apply()

    /** 自动压缩的触发点（有效窗口的百分比）；null 用 Claude Code 自己的。 */
    var autoCompactPercent: Int?
        get() = if (prefs.contains("autoCompactPercent")) prefs.getInt("autoCompactPercent", 0).takeIf { it in 1..100 } else null
        set(value) = prefs.edit().apply { if (value == null) remove("autoCompactPercent") else putInt("autoCompactPercent", value) }.apply()

    /** 存下来的全部设置的一份快照（备份导出、导入前比对用）。 */
    fun snapshot(): Map<String, *> = HashMap(prefs.all)

    /** 备份导入：一次写入这些项，值为 null 的删掉。写完才返回，成功返回 true。 */
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
        return editor.commit()
    }

    /** Claude Code 报过的各模型上下文窗口（“模型=窗口”）。旧会话重新打开、还没起进程时拿它算占比。 */
    var contextWindows: Map<String, Long>
        get() = prefs.getStringSet("contextWindows", null).orEmpty().mapNotNull { entry ->
            val parts = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            parts[1].toLongOrNull()?.let { parts[0] to it }
        }.toMap()
        set(value) = prefs.edit().putStringSet("contextWindows", value.map { "${it.key}=${it.value}" }.toSet()).apply()
}
