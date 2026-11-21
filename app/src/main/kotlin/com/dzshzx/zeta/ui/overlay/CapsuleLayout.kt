package com.dzshzx.zeta.ui.overlay

/** 展开画面里后台屏幕画布的尺寸（dp）。 */
internal data class StageSize(val width: Float, val height: Float)

/**
 * 胶囊展开画面的尺寸计算（纯函数，单元测试覆盖）。
 *
 * 竖屏时画布高 336dp（照 Eta）；横屏时屏幕只有三百多 dp 高，按固定高度会把下面的按钮挤出屏幕，
 * 所以画布高度按屏幕剩下的高度收小。宽度受展开胶囊的内宽限制：后台屏幕是横的时候按宽度算高，比例不变形。
 */
internal object CapsuleLayout {
    /** 展开画面窗口里画布以外的高度：标题行、画框留白、下方一行、按钮行和阴影边距（照 Eta 的字体量出来的 524 - 336）。 */
    const val STAGE_CHROME_DP = 188f

    const val STAGE_CANVAS_MAX_HEIGHT_DP = 336f

    /** 再矮就看不清了：宁可让窗口超出屏幕底边一点，也不把画布缩成一条。 */
    const val STAGE_CANVAS_MIN_HEIGHT_DP = 96f

    /** 展开胶囊 296dp，减去左右 12dp 外边距和画框两侧各 10dp 留白。 */
    const val STAGE_CANVAS_MAX_WIDTH_DP = 252f

    /** 窗口下沿离屏幕底边至少留这么多。 */
    const val BOTTOM_GAP_DP = 8f

    /** 后台屏幕的宽高比读不到时，按常见手机屏算。 */
    const val DEFAULT_RATIO = 9f / 19.5f

    /** 画布尺寸：[ratio] 是后台屏幕的宽 / 高，[screenHeightDp] 是胶囊所在屏幕的高，[topDp] 是胶囊窗口的上沿（状态栏下沿）。 */
    fun stageCanvas(ratio: Float, screenHeightDp: Float, topDp: Float): StageSize {
        val r = if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.3f, 3f) else DEFAULT_RATIO
        val room = screenHeightDp - topDp - STAGE_CHROME_DP - BOTTOM_GAP_DP
        val maxHeight = room.coerceIn(STAGE_CANVAS_MIN_HEIGHT_DP, STAGE_CANVAS_MAX_HEIGHT_DP)
        return if (maxHeight * r <= STAGE_CANVAS_MAX_WIDTH_DP) {
            StageSize(maxHeight * r, maxHeight)
        } else {
            StageSize(STAGE_CANVAS_MAX_WIDTH_DP, STAGE_CANVAS_MAX_WIDTH_DP / r)
        }
    }

    /** 展开画面时窗口的高度档（dp）。 */
    fun stageWindowHeight(canvas: StageSize): Float = STAGE_CHROME_DP + canvas.height
}
