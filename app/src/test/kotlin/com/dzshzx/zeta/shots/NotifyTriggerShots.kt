package com.dzshzx.zeta.shots

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.notify.NotifyRules
import com.dzshzx.zeta.core.notify.TimeWindow
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.TaskRun
import com.dzshzx.zeta.ui.settings.NotifyTriggerScreen
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

/** 通知触发：规则列表（JVM 上没有通知使用权，顶上是授予的提示）、编辑页的上半和下半。 */
class NotifyTriggerShots : ShotTest() {
    private fun seed() {
        val now = System.currentTimeMillis()
        val express =
            NotifyRules.recordFire(
                    NotifyRule(
                        id = "r1",
                        name = "快递取件提醒",
                        packageName = "com.example.express",
                        appLabel = "快递助手",
                        keywords = listOf("取件码", "驿站"),
                        prompt = "看看这条快递通知 {text}，把取件码和地址记进今天的待办",
                    ),
                    now,
                    ZoneId.systemDefault(),
                )
                .copy(
                    lastRun =
                        TaskRun(now - 60_000, RunOutcome.Done, "s-1", "已记下：取件码 1234，菜鸟驿站 3 号柜"),
                )
        val night =
            NotifyRule(
                id = "r2",
                name = "夜间工作群",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                window = TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)),
                prompt = "有人在群里找我时汇总一下",
                enabled = false,
                permissionMode = "bypassPermissions",
            )
        NotifyRuleStore.update(app) { listOf(express, night) }
    }

    @Test
    fun list() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("notify-triggers") {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    @Test
    fun editor() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("notify-trigger-editor", prepare = { onNodeWithText("快递取件提醒").performClick() }) {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 编辑页中段：指令用了 {text} 时的提醒、发给 Claude 的样子、防止失控。 */
    @Test
    fun editorMiddle() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot(
            "notify-trigger-editor-middle",
            prepare = {
                onNodeWithText("快递取件提醒").performClick()
                // 等编辑页滑进来再划，不然这一下落在动画里划不动；要的几行在 LazyColumn 里还没组合，不能 scrollIntoView。
                // 慢慢划半屏，不带惯性，停在中段
                mainClock.advanceTimeBy(600)
                waitForIdle()
                onRoot().performTouchInput {
                    swipeUp(startY = bottom * 0.85f, endY = bottom * 0.3f, durationMillis = 2000)
                }
            },
        ) {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 编辑页下半：预览、防止失控、运行方式（完全放行时的风险提示）。 */
    @Test
    fun editorRisk() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot(
            "notify-trigger-editor-risk",
            prepare = {
                onNodeWithText("夜间工作群").performClick()
                // 页面很长：往上划两次到“运行方式”那一组
                repeat(2) { onRoot().performTouchInput { swipeUp() } }
            },
        ) {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 编辑页最下面：只在有变化时提醒开着，上次运行没有变化（摘要是 Claude 的说明）。 */
    @Test
    fun editorOnlyOnChange() {
        val hub = Fixtures.readyHub(app)
        seed()
        NotifyRuleStore.updateRule(app, "r1") {
            it.copy(
                onlyOnChange = true,
                lastRun =
                    TaskRun(
                        System.currentTimeMillis() - 60_000,
                        RunOutcome.NoChange,
                        "s-1",
                        "不是取件通知，是广告",
                    ),
            )
        }
        shoot(
            "notify-trigger-only-on-change",
            prepare = {
                onNodeWithText("快递取件提醒").performClick()
                repeat(4) { onRoot().performTouchInput { swipeUp() } }
            },
        ) {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 选应用的页面（JVM 上没有可列的应用，只有“手动输入包名”）。 */
    @Test
    fun appPicker() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("notify-trigger-apps", prepare = { openPicker() }) {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    /** 选应用时在顶栏下的搜索框里输入：标题让位，换成搜索结果。 */
    @Test
    fun appPickerSearch() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot(
            "notify-trigger-apps-search",
            prepare = {
                openPicker()
                onNodeWithText("搜索应用名或包名").performClick()
                mainClock.advanceTimeBy(600)
                waitForIdle()
                onNode(hasSetTextAction() and hasText("搜索应用名或包名")).performTextInput("com.example")
            },
        ) {
            NotifyTriggerScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.openPicker() {
        onNodeWithText("快递取件提醒").performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithText("应用").performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
}
