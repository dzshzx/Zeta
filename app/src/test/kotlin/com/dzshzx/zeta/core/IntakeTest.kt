package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeTest {
    private val send = "android.intent.action.SEND"
    private val sendMultiple = "android.intent.action.SEND_MULTIPLE"
    private val view = "android.intent.action.VIEW"

    private fun parse(
        action: String?,
        text: String? = null,
        subject: String? = null,
        streams: List<String> = emptyList(),
        data: String? = null,
        clipUris: List<String> = emptyList(),
        clipTexts: List<String> = emptyList(),
    ) = IntakeParser.parse(action, text, subject, streams, data, clipUris, clipTexts)

    @Test
    fun sharedTextGoesToInput() {
        val intake = parse(send, text = "  看看这段  ")!!
        assertEquals(IntakeSource.Share, intake.source)
        assertEquals("看看这段", intake.text)
        assertTrue(intake.uris.isEmpty())
        assertEquals("一段文字", intake.summary)
    }

    @Test
    fun browserSubjectComesFirst() {
        assertEquals(
            "页面标题\nhttps://example.com/a",
            parse(send, text = "https://example.com/a", subject = "页面标题")!!.text,
        )
        // 文字里已经有标题就不重复
        assertEquals(
            "页面标题 https://example.com/a",
            parse(send, text = "页面标题 https://example.com/a", subject = "页面标题")!!.text,
        )
    }

    @Test
    fun streamsAndClipDataAreMergedWithoutDuplicates() {
        val intake =
            parse(
                sendMultiple,
                streams = listOf("content://a/1", "content://a/2"),
                clipUris = listOf("content://a/2", "content://a/3"),
                clipTexts = listOf("ignored when files are present"),
            )!!
        assertEquals(listOf("content://a/1", "content://a/2", "content://a/3"), intake.uris)
        assertNull(intake.text)
        assertEquals("3 个文件", intake.summary)
    }

    @Test
    fun textThatIsJustTheFileUriIsDropped() {
        val intake = parse(send, text = "content://a/1", streams = listOf("content://a/1"))!!
        assertNull(intake.text)
        assertEquals(listOf("content://a/1"), intake.uris)
    }

    @Test
    fun clipTextIsUsedWhenThereIsNoExtraText() {
        assertEquals("第一段\n第二段", parse(send, clipTexts = listOf(" 第一段 ", "", "第二段"))!!.text)
    }

    @Test
    fun viewOpensOneFile() {
        val intake = parse(view, data = "content://files/notes.md")!!
        assertEquals(IntakeSource.Open, intake.source)
        assertEquals(listOf("content://files/notes.md"), intake.uris)
        assertEquals("一个文件", intake.summary)
        assertNull(parse(view, data = null))
    }

    @Test
    fun otherIntentsAndEmptySharesAreIgnored() {
        assertNull(parse("android.intent.action.MAIN", text = "x"))
        assertNull(parse(null))
        assertNull(parse(send, text = "   "))
        assertNull(parse(sendMultiple, streams = listOf("")))
    }

    @Test
    fun pendingIntakesCombine() {
        val a = Intake(IntakeSource.Share, text = "一", uris = listOf("content://a/1"))
        val b =
            Intake(IntakeSource.Share, text = "二", uris = listOf("content://a/1", "content://a/2"))
        val both = a + b
        assertEquals("一\n二", both.text)
        assertEquals(listOf("content://a/1", "content://a/2"), both.uris)
        assertEquals(IntakeSource.Share, both.source)
        assertEquals("2 个文件和一段文字", both.summary)
        // 截屏和分享合在一起就不再算截屏
        assertEquals(IntakeSource.Share, (Intake(IntakeSource.Screenshot) + a).source)
        assertTrue(Intake(IntakeSource.Screenshot).isEmpty)
        assertFalse(a.isEmpty)
    }

    @Test
    fun summaryNamesScreenshots() {
        assertEquals(
            "一张截屏",
            intakeSummary(IntakeSource.Screenshot, hasText = false, files = 0, images = 1),
        )
        assertEquals(
            "2 张截屏",
            intakeSummary(IntakeSource.Screenshot, hasText = false, files = 0, images = 2),
        )
        assertEquals(
            "一张图片和一个文件",
            intakeSummary(IntakeSource.Share, hasText = false, files = 1, images = 1),
        )
    }

    @Test
    fun draftKeepsWhatWasTyped() {
        assertEquals("新的", IntakeParser.mergeDraft("", "新的"))
        assertEquals("新的", IntakeParser.mergeDraft("  \n", "新的"))
        assertEquals("已经打的\n新的", IntakeParser.mergeDraft("已经打的\n", "新的"))
    }

    @Test
    fun largeFilesShowSizeAndTooLargeOnesAreRefused() {
        assertEquals("a.pdf", FileLimits.label("a.pdf", null))
        assertEquals("a.pdf", FileLimits.label("a.pdf", 3_000_000L))
        assertEquals("a.pdf · 35 MB", FileLimits.label("a.pdf", 35_000_000L))
        assertFalse(FileLimits.tooLarge(null))
        assertFalse(FileLimits.tooLarge(FileLimits.MAX_BYTES))
        assertTrue(FileLimits.tooLarge(FileLimits.MAX_BYTES + 1))
        assertEquals(
            "「v.mp4」大小为 1.29 GB，超过 200 MB 上限，未复制",
            FileLimits.tooLargeReason("v.mp4", 1288490189L),
        )
        assertEquals("「v.mp4」超过 200 MB 上限，未复制", FileLimits.tooLargeReason("v.mp4", null))
    }

    @Test
    fun routingAsksOnlyWhenThereIsSomethingToContinue() {
        val blank = IntakeCandidate("new-1", blank = true, active = false)
        val used = IntakeCandidate("s-1", blank = false, active = false)
        val runningA = IntakeCandidate("s-2", blank = false, active = true)
        val idleB = IntakeCandidate("s-3", blank = false, active = false)
        val runningC = IntakeCandidate("s-4", blank = false, active = true)
        // 当前对话有内容：接着它（就算后台还有在跑的）
        assertEquals(used, IntakeRouting.pick(used, listOf(runningA)))
        // 当前是空白的新对话：接着后台最近切走、还在进行的那个
        assertEquals(runningC, IntakeRouting.pick(blank, listOf(runningA, idleB, runningC)))
        assertEquals(runningA, IntakeRouting.pick(blank, listOf(runningA, idleB)))
        // 都没有：不用问，直接放进当前的空白对话
        assertNull(IntakeRouting.pick(blank, listOf(idleB)))
        assertNull(IntakeRouting.pick(blank, emptyList()))
    }
}
