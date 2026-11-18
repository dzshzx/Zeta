package com.dzshzx.zeta.core

import android.content.Context
import java.util.concurrent.TimeUnit

/** Eta 的 Debian 环境里，Claude Code 所属的那个 Linux 用户。 */
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
    const val DEFAULT_ROOTFS = "/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs"

    private const val FIND_BUSYBOX = "BB=; for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do " +
        "[ -x \"\$b\" ] && BB=\$b && break; done; [ -n \"\$BB\" ] || { echo 'zeta: busybox not found' >&2; exit 126; }; "

    // 参数：$1 BusyBox，$2 rootfs，$3 要执行的命令，$4 家目录，$5 用户名，$6 uid，$7 gid
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
        exec "${'$'}BB" chroot "${'$'}R" /usr/bin/env -i HOME="${'$'}4" USER="${'$'}5" LOGNAME="${'$'}5" SHELL=/bin/bash \
          LANG=C.UTF-8 TERM=dumb TZ="${'$'}8" /usr/bin/setpriv --reuid="${'$'}6" --regid="${'$'}7" --init-groups /bin/bash -lc "${'$'}3"
    """.trimIndent()

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 给 `su -c` 的整段脚本：在 Debian 里以 [config] 的用户身份、登录 shell 的环境执行 [command]。 */
    fun enterScript(config: LinuxEnvConfig, command: String): String =
        FIND_BUSYBOX + "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" unshare -m --propagation private \"\$BB\" sh -c " +
            quote(MOUNT_AND_CHROOT) + " zeta \"\$BB\" " + quote(config.rootfs) + " " + quote(command) + " " +
            quote(config.home) + " " + quote(config.user) + " " + config.uid + " " + config.gid + " " +
            // 手机的时区：Debian 那边的系统时区多半是 UTC，不传的话 Claude Code 看到的日期时间会差几个小时
            quote(java.util.TimeZone.getDefault().id)

    fun start(config: LinuxEnvConfig, command: String): Process =
        ProcessBuilder("su", "-c", enterScript(config, command)).start()

    /** 以 root 在 Android 侧直接执行一段脚本（不进 Debian）。 */
    fun runRoot(script: String, timeoutMs: Long = 15_000): CommandResult = capture(ProcessBuilder("su", "-c", script).start(), null, timeoutMs)

    fun run(config: LinuxEnvConfig, command: String, stdin: String? = null, timeoutMs: Long = 30_000): CommandResult =
        capture(start(config, command), stdin, timeoutMs)

    private fun capture(process: Process, stdin: String?, timeoutMs: Long): CommandResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } }.apply { start() }
        val t2 = Thread { runCatching { err.append(process.errorStream.bufferedReader().readText()) } }.apply { start() }
        runCatching {
            process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray()) }
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy()
            return CommandResult(-1, out.toString(), "timeout")
        }
        t1.join(2000)
        t2.join(2000)
        return CommandResult(process.exitValue(), out.toString(), err.toString())
    }

    /** 能否拿到 root。没授权时 `su` 会直接失败或不存在。 */
    fun hasRoot(): Boolean = runCatching { runRoot("id -u", 30_000).let { it.exitCode == 0 && it.stdout.trim() == "0" } }.getOrDefault(false)

    /**
     * 从 rootfs 的 passwd 里找出装了 Claude Code 的普通用户（家目录下有 .claude），找不到就取第一个普通用户。
     * 返回 null 表示 rootfs 不存在或里面没有普通用户。
     */
    fun detect(rootfs: String = DEFAULT_ROOTFS): LinuxEnvConfig? {
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

    var env: LinuxEnvConfig?
        get() {
            val user = prefs.getString("user", null) ?: return null
            return LinuxEnvConfig(
                rootfs = prefs.getString("rootfs", LinuxEnv.DEFAULT_ROOTFS)!!,
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

    /** 新会话的权限模式；null 表示沿用 Claude Code 设置里的默认值。 */
    var permissionMode: String?
        get() = prefs.getString("permissionMode", null)
        set(value) = prefs.edit().putString("permissionMode", value).apply()
}
