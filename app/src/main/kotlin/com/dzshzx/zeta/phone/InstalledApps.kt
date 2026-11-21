package com.dzshzx.zeta.phone

import android.content.Context
import android.content.Intent

/** 手机上装的应用：有桌面图标的那些，和包名换应用名。手机工具、通知触发的选应用都用它。 */
object InstalledApps {
    /** 一个桌面图标：应用名、包名、启动它的 activity。同一个包可能有几个。 */
    class Launcher(val label: String, val packageName: String, val activity: String)

    /** 有桌面图标的应用，按系统给的顺序。 */
    fun launchable(context: Context): List<Launcher> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(intent, 0).map {
            Launcher(
                it.loadLabel(manager).toString(),
                it.activityInfo.packageName,
                it.activityInfo.name,
            )
        }
    }

    /** 按应用名或包名找：完全对上的（包名，或不分大小写的应用名）有就只要这些，没有再找名字、包名里含着它的。 */
    fun match(context: Context, query: String): List<Launcher> {
        val q = query.trim()
        val apps = launchable(context)
        apps
            .filter { it.packageName == q || it.label.equals(q, ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?.let {
                return it
            }
        return apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    /** 包名换成应用名；看不到的包（不在 Zeta 的可见范围里）或名字为空时用包名。 */
    fun label(context: Context, packageName: String): String =
        runCatching {
            context.packageManager.run {
                getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
            }
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: packageName

    /** 记着查过的名字，一批包名反复换名字时用。 */
    class Labels(private val context: Context) {
        private val cache = HashMap<String, String>()

        fun of(packageName: String): String =
            cache.getOrPut(packageName) { label(context, packageName) }
    }
}
