package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundStartTest {
    @Test
    fun appOpsModeMapsToAllowed() {
        assertEquals(true, backgroundStartModeAllowed(0))
        assertEquals(false, backgroundStartModeAllowed(1))
        assertEquals(false, backgroundStartModeAllowed(2))
        assertNull(backgroundStartModeAllowed(3))
        assertNull(backgroundStartModeAllowed(4))
    }

    @Test
    fun openRouteSkipsTheWaitWhenTheAnswerIsKnown() {
        assertEquals(
            OpenRoute.PendingIntent,
            OpenRoute.choose(foreground = false, backgroundStartAllowed = true, root = true),
        )
        assertEquals(
            OpenRoute.Root,
            OpenRoute.choose(foreground = false, backgroundStartAllowed = false, root = true),
        )
        assertEquals(
            OpenRoute.PendingIntentThenRoot,
            OpenRoute.choose(foreground = false, backgroundStartAllowed = null, root = true),
        )
        // Zeta 本来就在前台：系统不拦
        assertEquals(
            OpenRoute.PendingIntent,
            OpenRoute.choose(foreground = true, backgroundStartAllowed = false, root = true),
        )
        // 没有 root：只能试 PendingIntent
        assertEquals(
            OpenRoute.PendingIntent,
            OpenRoute.choose(foreground = false, backgroundStartAllowed = false, root = false),
        )
    }

    @Test
    fun grantScriptTargetsTheMiuiOp() {
        assertEquals(
            "cmd appops set --user \"\$3\" \"\$2\" 10021 allow",
            BackgroundStart.GRANT_SCRIPT,
        )
    }
}
