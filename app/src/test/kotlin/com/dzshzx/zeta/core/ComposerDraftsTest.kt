package com.dzshzx.zeta.core

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 输入栏草稿按对话保管（[ComposerDrafts]）：换对话、新对话拿到会话号、发出后清空、删除会话、Zeta 重开后恢复。 用真的 hub（不起 Claude
 * 进程）；“重开”是在同一份设置上再建一个 hub。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ComposerDraftsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val cwd = Fixtures.env.defaultCwd

    private fun ConversationHub.session(id: String) =
        Conversation(this, cwd, id, "会话 $id", historyFile = null)

    private fun ComposerDraft.type(text: String) = input.setTextAndPlaceCursorAtEnd(text)

    /** 核心报来了会话号（conv.state 的 session）。 */
    private fun Conversation.receiveSessionId(id: String) = core { it.copy(session = id) }

    private val prefs
        get() = app.getSharedPreferences(ComposerDrafts.PREFS, Context.MODE_PRIVATE)

    private fun stored(): List<DraftRecord> =
        DraftRecords.decode(prefs.getString(ComposerDrafts.KEY, null))

    @Test
    fun eachConversationKeepsItsOwn() {
        val hub = Fixtures.readyHub(app)
        val a = hub.drafts.of(hub.session("s-a"))
        val b = hub.drafts.of(hub.session("s-b"))
        assertNotSame(a, b)
        a.type("给甲的")
        a.uploads +=
            FileUpload("u1", "报告.pdf").also {
                it.path = "/home/dzshzx/.cache/zeta/files/ab12/报告.pdf"
            }
        hub.drafts.save(a)
        b.type("给乙的")
        hub.drafts.save(b)
        // 切走以后对话对象收掉了，重新打开是新建的对象：按会话号找回同一份
        assertSame(a, hub.drafts.of(hub.session("s-a")))
        assertEquals("给甲的", hub.drafts.of(hub.session("s-a")).input.text.toString())
        assertEquals("给乙的", hub.drafts.of(hub.session("s-b")).input.text.toString())
        assertEquals("给甲的", hub.drafts.preview("s-a"))
        assertEquals(setOf("s-a", "s-b"), stored().map { it.key }.toSet())
    }

    @Test
    fun newConversationsShareOneDraftUntilStarted() {
        val hub = Fixtures.readyHub(app)
        val fresh = hub.drafts.of(hub.current)
        fresh.type("还没想好怎么问")
        hub.drafts.save(fresh)
        // 切去别的会话再点“新对话”：没发出的字还在
        hub.park(hub.session("s-other"))
        hub.openConversation("s-other")
        assertTrue(hub.drafts.of(hub.current).input.text.isEmpty())
        hub.startNew()
        assertSame(fresh, hub.drafts.of(hub.current))
        assertEquals(listOf(ComposerDrafts.NEW), stored().map { it.key })
        // 新对话不进侧栏
        assertNull(hub.drafts.preview(ComposerDrafts.NEW))
    }

    @Test
    fun newConversationDraftFollowsItsSessionId() {
        val hub = Fixtures.readyHub(app)
        val started = hub.current
        val draft = hub.drafts.of(started)
        // 第一条发出去了（输入框清空），回答期间又写了半句；然后 Claude Code 报来了会话号
        started.coreItems(listOf(UserItem("u1", "第一条")))
        draft.type("接着想问")
        hub.drafts.save(draft)
        assertTrue("还没有会话号的不存盘", stored().isEmpty())
        started.receiveSessionId("s-new")
        assertEquals(listOf("s-new"), stored().map { it.key })
        assertEquals("接着想问", hub.drafts.preview("s-new"))
        assertSame(draft, hub.drafts.of(hub.session("s-new")))
        // 下一个新对话从空的开始
        hub.startNew()
        val next = hub.drafts.of(hub.current)
        assertNotSame(draft, next)
        assertTrue(next.input.text.isEmpty())
    }

    @Test
    fun sessionIdBeforeFirstMessageTakesTheNewDraft() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.current
        val draft = hub.drafts.of(conversation)
        draft.type("/compact")
        hub.drafts.save(draft)
        // 打斜杠命令时提前起了进程，会话号先到
        conversation.receiveSessionId("s-early")
        assertEquals(listOf("s-early"), stored().map { it.key })
        assertSame(draft, hub.drafts.of(conversation))
        hub.startNew()
        assertNotSame(draft, hub.drafts.of(hub.current))
    }

    @Test
    fun clearOnSessionChangeMovesDraft() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.session("s-old")
        val draft = hub.drafts.of(conversation)
        draft.type("清空上下文以后接着问")
        hub.drafts.save(draft)
        // /clear 换了新会话：草稿跟着这个对话走
        conversation.receiveSessionId("s-cleared")
        assertEquals(listOf("s-cleared"), stored().map { it.key })
        assertNull(hub.drafts.preview("s-old"))
        assertSame(draft, hub.drafts.of(hub.session("s-cleared")))
    }

    @Test
    fun imageFilesSendTheirDownscaledCopyAfterOtherImages() {
        val draft = ComposerDraft()
        val shot = jpeg(0xFFFF0000.toInt())
        val copy = jpeg(0xFF0000FF.toInt())
        val pending = jpeg(0xFF00FF00.toInt())
        draft.attachments += shot
        draft.uploads += FileUpload("u1", "a.txt").also { it.path = "/tmp/a.txt" }
        draft.uploads +=
            FileUpload("u2", "IMG_1.jpg").also {
                it.path = "/tmp/IMG_1.jpg"
                it.inline = copy
            }
        // 还在复制的原图：缩小图也先不发，免得和附件行对不上
        draft.uploads += FileUpload("u3", "IMG_2.jpg").also { it.inline = pending }
        assertEquals(listOf(shot, copy), draft.outgoingImages())
        assertEquals(listOf("/tmp/a.txt", "/tmp/IMG_1.jpg"), draft.outgoingFiles())
    }

    @Test
    fun puttingBackPairsDownscaledCopiesWithTheirFiles() {
        val draft = ComposerDraft()
        val shot = jpeg(0xFFFF0000.toInt())
        val copy = jpeg(0xFF0000FF.toInt())
        val files = listOf("/tmp/a.txt", "/tmp/IMG_1.jpg", "@/sdcard/b.png")
        for (path in files) draft.uploads += FileUpload(path, path).also { it.path = path }
        assertEquals(listOf(shot), draft.pairInline(listOf(shot, copy), files))
        assertSame(copy, draft.uploads[1].inline)
        assertNull(draft.uploads[2].inline)
        // 图比图片文件少：对不上，都当单独的图
        val other = ComposerDraft()
        other.uploads += FileUpload("x", "x").also { it.path = "/tmp/IMG_1.jpg" }
        other.uploads += FileUpload("y", "y").also { it.path = "/tmp/IMG_2.jpg" }
        assertEquals(
            listOf(shot),
            other.pairInline(listOf(shot), listOf("/tmp/IMG_1.jpg", "/tmp/IMG_2.jpg")),
        )
        assertNull(other.uploads[0].inline)
    }

    @Test
    fun savingEmptyDraftClearsIt() {
        val hub = Fixtures.readyHub(app)
        val draft = hub.drafts.of(hub.session("s-a"))
        draft.type("发出去就没了")
        draft.uploads += FileUpload("u1", "a.txt").also { it.path = "/tmp/a.txt" }
        hub.drafts.save(draft)
        assertEquals(1, stored().size)
        // 输入栏发送：先清空输入框和附件，再存
        draft.type("")
        draft.uploads.clear()
        hub.drafts.save(draft)
        assertFalse(prefs.contains(ComposerDrafts.KEY))
        assertNull(hub.drafts.preview("s-a"))
    }

    @Test
    fun uploadsStillCopyingAreNotStored() {
        val hub = Fixtures.readyHub(app)
        val draft = hub.drafts.of(hub.session("s-a"))
        val upload = FileUpload("u1", "大文件.zip")
        draft.uploads += upload
        hub.drafts.save(draft)
        assertTrue(stored().isEmpty())
        upload.path = "/home/dzshzx/.cache/zeta/files/cd34/大文件.zip"
        hub.drafts.save(draft)
        assertEquals(listOf(DraftFile(upload.path!!, "大文件.zip")), stored().single().files)
    }

    @Test
    fun deletingSessionForgetsDraft() {
        val hub = Fixtures.readyHub(app)
        val draft = hub.drafts.of(hub.session("s-gone"))
        draft.type("这个会话要删了")
        hub.drafts.save(draft)
        hub.drafts.of(hub.session("s-kept")).also { it.type("留着") }.let(hub.drafts::save)
        hub.sessionList.delete(
            listOf(
                SessionSummary(
                    "s-gone",
                    "/home/dzshzx/.claude/projects/-w/s-gone.jsonl",
                    cwd,
                    "要删的",
                    "",
                    0,
                ),
            ),
        )
        assertNull(hub.drafts.preview("s-gone"))
        assertEquals(listOf("s-kept"), stored().map { it.key })
        assertTrue(hub.drafts.of(hub.session("s-gone")).input.text.isEmpty())
        // 界面离开时再存一次已经删掉的那份：不会写回去
        hub.drafts.save(draft)
        assertEquals(listOf("s-kept"), stored().map { it.key })
    }

    @Test
    fun restoredAfterRestart() {
        val before = Fixtures.readyHub(app)
        val session = before.drafts.of(before.session("s-a"))
        session.input.edit {
            replace(0, length, "第一行\n第二行")
            selection = TextRange(2, 5)
        }
        session.uploads +=
            FileUpload("u1", "报告.pdf (1.2 MB)").also {
                it.path = "/home/dzshzx/.cache/zeta/files/ab12/报告.pdf"
            }
        session.uploads +=
            FileUpload("u2", "docs").also { it.path = "@/workspace/claude/zeta/docs/" }
        before.drafts.save(session)
        val fresh = before.drafts.of(before.current)
        fresh.type("新对话里写了一半")
        before.drafts.save(fresh)

        // Zeta 被杀后重开：新的 hub 读同一份设置
        val after = Fixtures.readyHub(app)
        assertEquals("第一行", after.drafts.preview("s-a"))
        val restored = after.drafts.of(after.session("s-a"))
        assertEquals("第一行\n第二行", restored.input.text.toString())
        assertEquals(TextRange(2, 5), restored.input.selection)
        // 进不了 Linux 环境（测试里没有 root）时查不了文件在不在，照原样留着
        awaitMain { true }
        assertEquals(listOf("报告.pdf (1.2 MB)", "docs"), restored.uploads.map { it.name })
        assertEquals(
            listOf("/home/dzshzx/.cache/zeta/files/ab12/报告.pdf", "@/workspace/claude/zeta/docs/"),
            restored.uploads.map { it.path },
        )
        assertEquals("新对话里写了一半", after.drafts.of(after.current).input.text.toString())
    }

    @Test
    fun imagesSurviveRestartAndMissingOnesAreDropped() {
        val before = Fixtures.readyHub(app)
        val draft = before.drafts.of(before.session("s-img"))
        val kept = jpeg(0xFF3A6EA5.toInt())
        val lost = jpeg(0xFFE0A030.toInt())
        draft.attachments += kept
        draft.attachments += lost
        before.drafts.save(draft)
        val dir = File(app.filesDir, "drafts")
        awaitMain { File(dir, kept.id).exists() && File(dir, lost.id).exists() }
        assertEquals(
            Base64.decode(kept.base64, Base64.NO_WRAP).toList(),
            File(dir, kept.id).readBytes().toList(),
        )
        // 系统或用户清掉了其中一张
        File(dir, lost.id).delete()

        val after = Fixtures.readyHub(app)
        assertEquals("2 张图片", after.drafts.preview("s-img"))
        val restored = after.drafts.of(after.session("s-img"))
        awaitMain { restored.attachments.isNotEmpty() && stored().single().images.size == 1 }
        assertEquals(listOf(kept.id), restored.attachments.map { it.id })
        assertEquals(kept.base64, restored.attachments.single().base64)
        assertEquals("1 张图片", after.drafts.preview("s-img"))
    }

    @Test
    fun imageFilesOfClearedDraftsAreDeleted() {
        val hub = Fixtures.readyHub(app)
        val draft = hub.drafts.of(hub.session("s-img"))
        val image = jpeg(0xFF3A6EA5.toInt())
        draft.attachments += image
        hub.drafts.save(draft)
        val file = File(File(app.filesDir, "drafts"), image.id)
        awaitMain { file.exists() }
        draft.attachments.clear()
        hub.drafts.save(draft)
        awaitMain { !file.exists() }
        assertFalse(file.exists())
    }

    private fun jpeg(color: Int): Attachment {
        val bitmap =
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                .toByteArray()
        return Attachment(
            UUID.randomUUID().toString(),
            bitmap.asImageBitmap(),
            "image/jpeg",
            Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }
}
