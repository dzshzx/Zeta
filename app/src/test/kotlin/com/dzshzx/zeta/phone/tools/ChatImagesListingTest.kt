package com.dzshzx.zeta.phone.tools

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ChatImages.newest] 的目录加倍：用编造的缓存（目录的修改时间、文件的修改时间）照 listScript 的规则模拟每一次列目录， 和整个扫一遍的前 N 条比。 */
class ChatImagesListingTest {
    private val slack = ChatImages.SLACK_MS

    /** 编造的缓存：[dirs] 是目录和它的修改时间，[files] 是（修改时间、大小、路径），路径的上一级是它所在的目录。 */
    private class Cache(val dirs: Map<String, Long>, val files: List<Triple<Long, Long, String>>) {
        val asked = mutableListOf<Int>()

        /** 照 listScript：dirs 为 0 时整个扫；否则选窗口结束以后改过的全部目录，再往前取最新的 [want] 个。 */
        fun pass(want: Int, max: Int, start: Long? = null, end: Long? = null): ChatImages.Listing {
            asked += want
            fun inWindow(t: Long) = (start == null || t >= start) && (end == null || t < end)
            val all = files.filter { inWindow(it.first) }.sortedByDescending { it.first }
            if (want == 0) return ChatImages.Listing(all.take(max), null)
            val ranked =
                dirs.entries
                    .filter { start == null || it.value >= start - ChatImages.SLACK_MS }
                    .sortedByDescending { it.value }
            val chosen = HashSet<String>()
            var older = 0
            var next: Long? = null
            for ((dir, time) in ranked) {
                if ((end != null && time >= end) || ++older <= want) chosen += dir
                else {
                    next = time
                    break
                }
            }
            if (chosen.size > ChatImages.MAX_DIRS) return ChatImages.Listing(all.take(max), null)
            return ChatImages.Listing(
                all.filter { it.third.substringBeforeLast('/') in chosen }.take(max),
                next,
            )
        }

        fun full(max: Int, start: Long? = null, end: Long? = null) =
            pass(0, max, start, end).lines.also { asked.removeAt(asked.lastIndex) }
    }

    /** [count] 个目录，每个里面 [perDir] 个文件，时间依次往后；目录的修改时间是其中最新文件建立的时间。 */
    private fun steady(count: Int, perDir: Int): Cache {
        val files =
            (0 until count * perDir).map { i ->
                Triple(1_000_000L + i * 10_000L, 100L, "/c/d${i % count}/f$i")
            }
        return Cache(
            files
                .groupBy { it.third.substringBeforeLast('/') }
                .mapValues { (_, f) -> f.maxOf { it.first } },
            files,
        )
    }

    @Test
    fun usuallyOnePassOverTheNewestDirectories() {
        val cache = steady(5_000, 3)
        val got = ChatImages.newest(63) { cache.pass(it, 63) }
        assertEquals(cache.full(63), got)
        assertEquals(listOf(126), cache.asked)
    }

    @Test
    fun doublesWhenRecentDirectoriesHoldOnlyOldFiles() {
        val base = steady(5_000, 3)
        // 清理缓存删掉了 500 个目录里的新文件：目录的修改时间是最近，里面剩下的都是旧的
        val now = base.files.maxOf { it.first } + 3_600_000
        val touched = base.dirs.keys.take(500).associateWith { now }
        val cache =
            Cache(
                base.dirs + touched,
                base.files.filter {
                    it.first < 1_000_000L + 10_000L * 5_000 ||
                        it.third.substringBeforeLast('/') !in touched
                },
            )
        val got = ChatImages.newest(63) { cache.pass(it, 63) }
        assertEquals(cache.full(63), got)
        assertEquals(listOf(126, 252, 504, 1_008), cache.asked)
    }

    @Test
    fun stopsOnlyWhenTheLastWantedFileBeatsUnlistedDirectoriesByTheSlack() {
        fun listing(last: Long, next: Long?) =
            ChatImages.Listing(listOf(Triple(last + 5, 1L, "/a/1"), Triple(last, 1L, "/a/2")), next)
        // 文件可能比它的目录晚写完一点：没选上的目录里的文件最晚可到 next + 余量
        assertTrue(listing(1_000_000 + slack + 1, 1_000_000).covers(2))
        assertFalse(listing(1_000_000 + slack, 1_000_000).covers(2))
        // 条数不够、又还有没选上的目录：不够
        assertFalse(listing(9_000_000, 1_000_000).covers(3))
        // 目录都选上了：够
        assertTrue(listing(0, null).covers(3))
    }

    @Test
    fun fewerFilesThanWantedListsEveryDirectory() {
        val cache = steady(40, 1)
        val got = ChatImages.newest(63) { cache.pass(it, 63) }
        assertEquals(cache.full(63), got)
        assertEquals(40, got!!.size)
        assertEquals(listOf(126), cache.asked)
    }

    @Test
    fun givesUpOnDirectoriesAndScansEverythingPastTheLimit() {
        val base = steady(20_000, 1)
        // 3500 个目录被清理碰过（修改时间是最近，里面只剩旧文件）：3000 个目录还不够，再加倍就超过上限，整个扫一遍
        val now = base.files.maxOf { it.first } + 3_600_000
        val touched = base.dirs.keys.take(3_500).associateWith { now }
        val cache = Cache(base.dirs + touched, base.files)
        val got = ChatImages.newest(1_500) { cache.pass(it, 1_500) }
        assertEquals(cache.full(1_500), got)
        assertEquals(listOf(3_000, 0), cache.asked)
        // 要的太多：直接整个扫
        cache.asked.clear()
        ChatImages.newest(3_000) { cache.pass(it, 3_000) }
        assertEquals(listOf(0), cache.asked)
    }

    @Test
    fun tooManyDirectoriesAfterTheWindowMeansAFullScan() {
        // 窗口在所有文件之前：窗口结束以后改过的目录太多，脚本改成整个扫一遍，没有 next，一次就够
        val cache = steady(10_000, 1)
        val got = ChatImages.newest(63) { cache.pass(it, 63, start = 0, end = 1_000_000L) }
        assertEquals(emptyList<Triple<Long, Long, String>>(), got)
        assertEquals(listOf(126), cache.asked)
    }

    @Test
    fun directoriesChangedAfterTheWindowAreAlwaysIncluded() {
        val cache = steady(5_000, 3)
        val end = 1_000_000L + 10_000L * 14_000
        val got = ChatImages.newest(21) { cache.pass(it, 21, end = end) }
        assertEquals(cache.full(21, end = end), got)
        assertEquals(listOf(64), cache.asked)
    }

    @Test
    fun missingCacheIsNull() {
        assertNull(ChatImages.newest(63) { null })
    }

    @Test
    fun matchesTheFullScanOnRandomCaches() {
        val random = Random(17)
        repeat(200) { round ->
            val dirCount = random.nextInt(1, 3_000)
            val files = ArrayList<Triple<Long, Long, String>>()
            val dirTime = HashMap<String, Long>()
            repeat(random.nextInt(1, 8_000)) { i ->
                val dir = "/r/d${random.nextInt(dirCount)}"
                val created = random.nextLong(0, 1_000_000_000L)
                // 文件写完可能比建立晚一点（最多 1 秒）
                files += Triple(created + random.nextLong(0, 1_000), 1L, "$dir/f$i")
                dirTime[dir] = maxOf(dirTime[dir] ?: 0, created)
            }
            // 有的目录被清理碰过：修改时间是最近，文件没变
            repeat(random.nextInt(0, 300)) {
                val dir = "/r/d${random.nextInt(dirCount)}"
                dirTime[dir] =
                    maxOf(dirTime[dir] ?: 0, random.nextLong(900_000_000L, 1_000_000_000L))
            }
            val max = random.nextInt(1, 200)
            val start = if (random.nextBoolean()) random.nextLong(0, 1_000_000_000L) else null
            val end =
                if (random.nextBoolean()) random.nextLong(start ?: 0, 1_000_000_001L) else null
            val cache = Cache(dirTime, files)
            val got = ChatImages.newest(max) { cache.pass(it, max, start, end) }
            assertEquals("第 $round 次", cache.full(max, start, end), got)
            assertTrue(cache.asked.toString(), cache.asked.size <= 8)
        }
    }
}
