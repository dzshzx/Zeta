package com.dzshzx.zeta.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.dzshzx.zeta.core.AppVariant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 把 Zeta 进程里 ImageReader 的 Surface 交给 root 辅助进程 [VdHelper]：Surface 只能经 Binder 跨进程传， 这是最省事的一条路。只认
 * root 和 system 身份的调用，每个 token 只能取一次，15 秒后作废。
 */
class VdSurfaceProvider : ContentProvider() {
    /**
     * 进程每次启动都会先建 Provider（哪怕只是系统为无障碍服务拉起进程、界面没打开）： 正好在这里恢复上次没来得及恢复的音频焦点。后台屏幕随上一个进程一起没了，这时恢复全部是对的。
     */
    override fun onCreate(): Boolean {
        val context = context ?: return true
        thread(name = "zeta-audio-focus", isDaemon = true) {
            AudioFocusGuard.of(context).restoreLeftovers()
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) return null
        if (method != METHOD || arg == null) return null
        val surface = consume(arg) ?: return Bundle()
        return Bundle().apply { putParcelable(EXTRA_SURFACE, surface) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** 和 Manifest 里的 `${applicationId}.background` 一致：正式版和测试版的 authority 不能相同，否则装不上。 */
        val AUTHORITY = AppVariant.current.authority("background")
        const val METHOD = "surface"
        const val EXTRA_SURFACE = "surface"
        private const val TTL_MS = 15_000L

        private class Entry(val surface: Surface, val at: Long = SystemClock.elapsedRealtime())

        private val entries = ConcurrentHashMap<String, Entry>()

        fun register(surface: Surface): String {
            prune()
            return UUID.randomUUID().toString().also { entries[it] = Entry(surface) }
        }

        fun unregister(token: String) {
            entries.remove(token)
        }

        private fun consume(token: String): Surface? {
            prune()
            return entries.remove(token)?.surface?.takeIf { it.isValid }
        }

        private fun prune() {
            val now = SystemClock.elapsedRealtime()
            entries.entries.removeIf { now - it.value.at > TTL_MS }
        }
    }
}
