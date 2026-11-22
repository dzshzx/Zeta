package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.TaskRun
import com.dzshzx.zeta.ui.settings.ScheduleScreen
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

/** 定时任务：列表（JVM 上没有精确闹钟权限，顶上是授予的提示）、编辑页的上半和下半、选时间的对话框， 以及只在有变化时提醒、上次没有变化的那个任务的编辑页下半。 */
class ScheduleShots : ShotTest() {
    private fun seed() {
        val now = System.currentTimeMillis()
        val brief =
            ScheduledTask(
                id = "t1",
                name = "每日简报",
                prompt = "汇总今天的日程和昨晚以来的通知，三五行说完",
                rule = ScheduleRule.Daily(LocalTime.of(8, 0)),
                created = now - 86_400_000,
                lastRun = TaskRun(now - 3_600_000, RunOutcome.Done, "s-1", "今天有两个会：10 点周会、15 点评审"),
                skips = 1,
                lastSkip = now - 2 * 86_400_000,
            )
        val weekly =
            ScheduledTask(
                id = "t2",
                name = "周报",
                prompt = "汇总这周的提交和改动，按项目分组",
                rule =
                    ScheduleRule.Weekly(
                        setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                        LocalTime.of(17, 30),
                    ),
                enabled = false,
                permissionMode = "acceptEdits",
                created = now - 7 * 86_400_000,
            )
        val watch =
            ScheduledTask(
                id = "t3",
                name = "降价监控",
                prompt = "看看购物车里那台显示器降价没有，降到 2800 以下告诉我",
                rule =
                    ScheduleRule.Interval(
                        3,
                        LocalDateTime.now().withHour(9).withMinute(0).withSecond(0).withNano(0),
                    ),
                onlyOnChange = true,
                created = now - 3 * 86_400_000,
                lastRun = TaskRun(now - 1_800_000, RunOutcome.NoChange, "s-3", "还是 2999，没有降价"),
            )
        ScheduleStore.update(app) { listOf(brief, weekly, watch) }
    }

    @Test
    fun list() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("schedules") {
            ScheduleScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 编辑页上半：名称、指令、每周的星期选择。 */
    @Test
    fun editor() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("schedule-editor", prepare = { onNodeWithText("周报").performClick() }) {
            ScheduleScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 编辑页下半：运行方式、运行记录、删除。 */
    @Test
    fun editorBottom() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot(
            "schedule-editor-bottom",
            prepare = {
                onNodeWithText("每日简报").performClick()
                repeat(2) { onRoot().performTouchInput { swipeUp() } }
            },
        ) {
            ScheduleScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 只在有变化时提醒的任务：编辑页下半的“提醒”一组，上次运行是“没有变化”和 Claude 的说明。 */
    @Test
    fun onlyOnChange() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot(
            "schedule-only-on-change",
            prepare = {
                onNodeWithText("降价监控").performClick()
                repeat(3) { onRoot().performTouchInput { swipeUp() } }
            },
        ) {
            ScheduleScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    @Test
    fun timeDialog() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot(
            "schedule-time",
            prepare = {
                onNodeWithText("每日简报").performClick()
                // 编辑页滑进来以后才有“08:00”那一行
                mainClock.advanceTimeBy(600)
                waitForIdle()
                onNodeWithText("08:00").performClick()
            },
        ) {
            ScheduleScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }
}
