package com.dzshzx.zeta.ui

import android.content.pm.PackageManager
import android.os.Looper
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer

/**
 * 启动检查：合入前不再上设备跑冒烟测试，「装上能不能起来」由这里在门禁里查。查的是真机上一启动就崩、单元测试却照过的那类问题：
 * 清单里登记的组件类找不到（类改了名、挪了包，清单没跟着改），以及冷启动路径（onCreate 到画出第一帧）上抛出的异常。 测的是门禁构建的这份清单（测试版，带它自己的组件）。经 R8
 * 以后还起不起得来不在这里查，见 docs/release.md 发版前的冒烟测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppStartTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun everyManifestComponentClassExists() {
        val flags =
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS
        val info = app.packageManager.getPackageInfo(app.packageName, flags)
        // activity-alias 指向别的 activity，它的类名是 alias 自己的名字，不是一个类
        val aliases =
            info.activities.orEmpty().filter { it.targetActivity != null }.map { it.name }.toSet()
        val names =
            (info.activities.orEmpty().map { it.name }.filterNot { it in aliases } +
                info.services.orEmpty().map { it.name } +
                info.receivers.orEmpty().map { it.name } +
                info.providers.orEmpty().map { it.name })
        assertTrue("清单里一个组件都没读到：${app.packageName}", names.size > 5)
        val missing = names.filter { runCatching { Class.forName(it) }.isFailure }
        assertEquals("清单里登记了、却找不到类的组件", emptyList<String>(), missing)
    }

    @Test
    fun coldStartFromLauncherDrawsTheUi() {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        assertNotNull("清单里没有桌面图标的启动入口", launch)
        // 帧只在下面推进的那些里跑：不暂停时 setup() 会把主线程跑到空闲为止，启动后界面上有一直转的动画（等待中的跳动圆点）
        // 就永远等不到空闲，空转到内存耗尽。推进的 120 帧（约 2 秒）足够组合、画出第一帧、跑完启动时的 LaunchedEffect
        ShadowChoreographer.setPaused(true)
        // 主线程协程里没接住的异常（比如 LaunchedEffect 里抛的）在真机上让应用崩溃，Robolectric 里却只打印出来：记下来，最后核对
        val thrown = mutableListOf<Throwable>()
        val main = Thread.currentThread()
        val previous = main.uncaughtExceptionHandler
        main.setUncaughtExceptionHandler { _, e -> thrown += e }
        try {
            Robolectric.buildActivity(MainActivity::class.java, launch).use { controller ->
                val activity = controller.setup().get()
                repeat(120) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17)) }
                assertFalse("启动后自己结束了", activity.isFinishing)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue("启动后什么都没画", content.childCount > 0)
            }
        } finally {
            main.uncaughtExceptionHandler = previous
        }
        assertEquals("冷启动时主线程上没接住的异常", emptyList<String>(), thrown.map { it.toString() })
    }
}
