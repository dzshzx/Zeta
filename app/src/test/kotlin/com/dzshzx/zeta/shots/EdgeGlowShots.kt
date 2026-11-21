package com.dzshzx.zeta.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.ui.overlay.EdgeGlow
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.junit.Test

class EdgeGlowShots : ShotTest() {
    /** 主屏边缘光，三块缩小的“屏幕”并排：运行中的流光、暂停的橙色描边、接管的紫色描边。 */
    @Test
    fun edgeGlow() {
        shoot("edge-glow", target = hasTestTag("glows")) {
            val corner = with(LocalDensity.current) { 32.dp.toPx() }
            Row(
                modifier =
                    Modifier.testTag("glows")
                        .fillMaxWidth()
                        .background(COUITheme.colorScheme.backgroundWithCard)
                        .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (phase in
                    listOf(OverlayPhase.RUNNING, OverlayPhase.PAUSED, OverlayPhase.CONTROLLING)) {
                    Box(
                        Modifier.weight(1f)
                            .height(240.dp)
                            .background(COUITheme.colorScheme.background),
                    ) {
                        EdgeGlow(phase, visible = true, hidden = false, cornerRadius = corner)
                    }
                }
            }
        }
    }
}
