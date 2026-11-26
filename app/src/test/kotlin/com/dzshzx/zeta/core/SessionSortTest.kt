package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 侧栏的两种排法（[SessionSort]）：会话项里的两个时间怎么读，按选的那个排，记录文件的修改时刻不参与。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionSortTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun parsed(fields: String) =
        sessionSummaryOf(
            Json.parseToJsonElement("""{"id":"s1","file":"/f/s1.jsonl",$fields}""").jsonObject,
            Backends.LOCAL,
        )!!

    private fun session(id: String, updated: Long, created: Long, replied: Long) =
        SessionSummary(
            id,
            "/p/$id.jsonl",
            "/w",
            id,
            "",
            updated,
            created = created,
            replied = replied,
        )

    @Test
    fun timesAreReadFromTheItem() {
        val s = parsed(""""updated":300,"created":100,"replied":200""")
        assertEquals(300L, s.updated)
        assertEquals(100L, s.created)
        assertEquals(200L, s.replied)
    }

    @Test
    fun storedKeysMapBackAndUnknownIsTheDefault() {
        assertEquals(SessionSort.Replied, SessionSort.Default)
        assertEquals(SessionSort.Created, SessionSort.of("created"))
        assertEquals(SessionSort.Replied, SessionSort.of("replied"))
        assertEquals(SessionSort.Replied, SessionSort.of(null))
        assertEquals(SessionSort.Replied, SessionSort.of("rank"))
    }

    /** a 发起得最早、最近才回答；b 工具结果刚写过（updated 最新）但回答得早；c 最晚发起。 */
    private val a = session("a", updated = 50, created = 10, replied = 40)
    private val b = session("b", updated = 90, created = 20, replied = 25)
    private val c = session("c", updated = 30, created = 30, replied = 30)

    @Test
    fun orderFollowsTheChosenTime() {
        val list = listOf(b, c, a)
        assertEquals(listOf("a", "c", "b"), SessionSort.Replied.ordered(list).map { it.id })
        assertEquals(listOf("c", "b", "a"), SessionSort.Created.ordered(list).map { it.id })
    }

    @Test
    fun settingIsStored() {
        val hub = Fixtures.readyHub(app)
        assertEquals(SessionSort.Replied, hub.settings.sessionSort)
        hub.settings.sessionSort = SessionSort.Created
        assertEquals(SessionSort.Created, Settings.of(app).sessionSort)
    }
}
