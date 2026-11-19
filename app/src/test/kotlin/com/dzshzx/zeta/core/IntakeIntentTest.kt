package com.dzshzx.zeta.core

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用真的 Intent（Robolectric）走一遍 [Intake.fromIntent]：别的应用发来的样子，am start 造不出 ArrayList<Uri>。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IntakeIntentTest {
    @Test
    fun sendText() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/a")
            .putExtra(Intent.EXTRA_SUBJECT, "标题")
        val intake = Intake.fromIntent(intent)!!
        assertEquals("标题\nhttps://example.com/a", intake.text)
        assertEquals(emptyList<String>(), intake.uris)
    }

    @Test
    fun sendMultipleUris() {
        val uris = arrayListOf(Uri.parse("content://files/a.txt"), Uri.parse("content://files/b.png"))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        // 系统会把 EXTRA_STREAM 搬一份进 ClipData（授权跟着它走），两边重复的只算一次
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { addItem(ClipData.Item(uris[1])) }
        val intake = Intake.fromIntent(intent)!!
        assertEquals(listOf("content://files/a.txt", "content://files/b.png"), intake.uris)
        assertNull(intake.text)
        assertEquals(IntakeSource.Share, intake.source)
    }

    @Test
    fun sendOneStream() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://files/r.pdf"))
        assertEquals(listOf("content://files/r.pdf"), Intake.fromIntent(intent)!!.uris)
    }

    @Test
    fun viewOpensData() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://files/notes.md"), "text/markdown")
        val intake = Intake.fromIntent(intent)!!
        assertEquals(IntakeSource.Open, intake.source)
        assertEquals(listOf("content://files/notes.md"), intake.uris)
        assertNull(Intake.fromIntent(Intent(Intent.ACTION_MAIN)))
    }
}
