package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dzshzx.zeta.shots.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 界面看不见时，经 [whileStarted] 读的状态变了也不重组：聊天页靠它在后台不随流式回答重组、测量。
 * 借截图测试的 Robolectric 环境，不出图。
 */
class WhileStartedTest : ShotTest() {
    @Test
    fun followsOnlyWhileTheScreenIsStarted() {
        val owner = TestOwner()
        var source by mutableStateOf(1)
        var seen = 0
        var compositions = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                seen = whileStarted { source }
                SideEffect { compositions++ }
            }
        }
        compose.runOnUiThread { source = 2 }
        compose.waitForIdle()
        assertEquals("看得见时跟着变", 2, seen)

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        val before = compositions
        compose.runOnUiThread { source = 3 }
        compose.waitForIdle()
        assertEquals("看不见时停在最后一份", 2, seen)
        assertEquals("看不见时值变了也不重组", before, compositions)

        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("回到前台补上最新的", 3, seen)
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
