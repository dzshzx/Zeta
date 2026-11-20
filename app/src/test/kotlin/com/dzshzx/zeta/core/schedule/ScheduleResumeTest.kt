package com.dzshzx.zeta.core.schedule

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.awaitMain
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 接着原对话的后台运行：先按会话号查原来的会话。查询没跑成（JVM 上的 su 总是失败，和手机上 Linux 环境没响应一样）
 * 不等于会话被删了：这次记为没有运行，任务仍然接着原来的会话，不另开新对话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ScheduleResumeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class MemoryRecords(var record: RunRecord) : RunRecords {
        override val kind = "test"
        override val fallbackName = "测试任务"
        override fun name(context: Context, id: String) = "接着原对话的任务"
        override fun update(context: Context, id: String, change: (RunRecord) -> RunRecord) {
            record = change(record)
        }
    }

    @Before
    fun reset() = Fixtures.install(null)

    @Test
    fun lookupFailureKeepsTheOriginalSession() {
        Fixtures.readyHub(app)
        val records = MemoryRecords(RunRecord(lastRun = null, skips = 0, lastSkip = null, sessionId = "s-old"))
        val spec = RunSpec(
            id = "t", name = "接着原对话的任务", prompt = "看一下", cwd = null, model = null,
            permissionMode = "default", newSession = false, sessionId = "s-old", hint = "",
        )
        assertNull("查询在后台做，这一步不报原因", ScheduleRunner.start(app, records, spec, manual = true))
        // 查询在后台线程上做，结果投回主线程
        awaitMain { records.record.lastRun != null }
        val run = records.record.lastRun!!
        assertEquals("记为没有运行：$run", RunOutcome.Failed, run.outcome)
        assertTrue(run.summary, run.summary.contains("查不到原来的对话"))
        assertEquals("任务仍然接着原来的会话", "s-old", records.record.sessionId)
        assertFalse("没有留下进行中的运行", ScheduleRunner.isRunning(records, spec.id))
    }
}
