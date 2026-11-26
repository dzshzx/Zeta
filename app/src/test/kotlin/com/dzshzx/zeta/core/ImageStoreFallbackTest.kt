package com.dzshzx.zeta.core

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.testPicture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 回答里写的图片文件读不到时，改用 Claude 用 Read 读它时留下的那张图（ImageStore.load 的 fallback）。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ImageStoreFallbackTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun clear() = ImageStore.clearMemory()

    @Test
    fun missingFileFallsBackToTheReadImageAndRemembersIt() = runBlocking {
        val hub = Fixtures.readyHub(app)
        // 测试里没有 Linux 环境，手机本机的文件一律读不到，和文件被删了一样
        val file = FileImage("/workspace/gone/shot.png")
        val read =
            InlineImage("t:read-shot", testPicture(4, 3, Color.RED, "").substringAfter(";base64,"))
        val backend = Backends.LOCAL

        assertNull(ImageStore.load(hub, file, backend))

        val shown = ImageStore.load(hub, file, backend, fallback = { read })
        assertNotNull(shown)
        assertEquals(4 to 3, shown!!.width to shown.height)
        // 看大图、保存用的也是代替它的那张
        assertSame(shown, ImageStore.cached(file, backend))
        assertNotNull(ImageStore.original(file, backend))
        // 记住了替换：之后不再先读文件，也不再查
        assertSame(shown, ImageStore.load(hub, file, backend, fallback = { error("不该再查") }))

        ImageStore.clearMemory()
        assertNull(ImageStore.cached(file, backend))
    }

    @Test
    fun noReadImageMeansUnavailable() = runBlocking {
        val hub = Fixtures.readyHub(app)
        val file = FileImage("/workspace/gone/other.png")
        assertNull(ImageStore.load(hub, file, Backends.LOCAL, fallback = { null }))
        assertNull(ImageStore.cached(file, Backends.LOCAL))
    }
}
