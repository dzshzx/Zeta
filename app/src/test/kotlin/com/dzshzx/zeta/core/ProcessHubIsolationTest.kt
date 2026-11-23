package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.components.ProcessHub
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner

/** 上一个用例登记的 hub 不留到下一个用例（清空见 [com.dzshzx.zeta.ZetaTestApplication]）。两个用例按名字顺序在同一个进程里跑。 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessHubIsolationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun a_leavesAHubBehind() {
        Fixtures.readyHub(app)
        assertNotNull(ProcessHub.current)
    }

    @Test
    fun b_nextTestStartsWithoutIt() {
        assertNull("上一个用例登记的 hub 留到了这个用例里", ProcessHub.current)
    }
}
