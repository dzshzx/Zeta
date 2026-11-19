package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
        val express = NotifyRules.recordFire(
            NotifyRule(
                id = "r1", name = "快递取件提醒", packageName = "com.example.express", appLabel = "快递助手",
                keywords = listOf("取件码", "驿站"), prompt = "看看这条快递通知 {text}，把取件码和地址记进今天的待办",
            ),
            now, ZoneId.systemDefault(),
        ).copy(lastRun = TaskRun(now - 60_000, RunOutcome.Done, "s-1", "已记下：取件码 1234，菜鸟驿站 3 号柜"))
        val night = NotifyRule(
            id = "r2", name = "夜间工作群", packageName = "com.tencent.mm", appLabel = "微信",
            window = TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)), prompt = "有人在群里找我时汇总一下", enabled = false,
            permissionMode = "bypassPermissions",
        )
        NotifyRuleStore.update(app) { listOf(express, night) }
    }

    @Test
    fun list() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("notify-triggers") { NotifyTriggerScreen(hub = hub, onBack = {}, onOpenConversation = {}) }
    }

    @Test
    fun editor() {
        val hub = Fixtures.readyHub(app)
        seed()
        shoot("notify-trigger-editor", prepare = { onNodeWithText("快递取件提醒").performClick() }) {
            NotifyTriggerScreen(hub = hub, onBack = {}, onOpenConversation = {})
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
            NotifyTriggerScreen(hub = hub, onBack = {}, onOpenConversation = {})
        }
    }
}
