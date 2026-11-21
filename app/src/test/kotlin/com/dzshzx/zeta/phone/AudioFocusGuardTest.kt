package com.dzshzx.zeta.phone

import com.dzshzx.zeta.core.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusGuardTest {
    /**
     * 一台假手机：按 AOSP `cmd appops` 的样子回答 get、set。[modes] 是包级模式（没有就是没设过、也没用过），[uidModes] 是 uid 级的。
     * [defaultMode] 是这个操作的默认模式：Android 17 的 HyperOS 上是 foreground，老系统是 allow。 [log]
     * 按先后记下每条命令和每次存记录，用来核对先后顺序。
     */
    private class Phone(val defaultMode: String = "foreground") {
        val installed = mutableSetOf("com.miui.player", "com.example.video", "com.example.radio")
        val modes = HashMap<String, String>()
        val uidModes = HashMap<String, String>()
        var rootWorks = true
        var setWorks = true
        val log = ArrayList<String>()

        fun shell(command: String): CommandResult {
            log += command
            if (!rootWorks) return CommandResult(1, "", "su: permission denied")
            val parts = command.split(' ')
            val verb = parts[2]
            val pkg = parts[5].trim('\'')
            if (pkg !in installed) return CommandResult(255, "", "Error: No UID for $pkg in user 0")
            return when (verb) {
                "get" -> {
                    val out = buildString {
                        uidModes[pkg]?.let { append("Uid mode: TAKE_AUDIO_FOCUS: $it\n") }
                        val mode = modes[pkg]
                        when {
                            mode != null -> append("TAKE_AUDIO_FOCUS: $mode; time=+3m12s ago\n")
                            uidModes[pkg] == null ->
                                append("No operations.\nDefault mode: $defaultMode\n")
                        }
                    }
                    CommandResult(0, out, "")
                }
                "set" -> {
                    if (!setWorks) return CommandResult(1, "", "timeout")
                    modes[pkg] = parts[7]
                    CommandResult(0, "", "")
                }
                else -> CommandResult(1, "", "unknown")
            }
        }

        fun mode(pkg: String): String = modes[pkg] ?: defaultMode
    }

    private class Store(
        initial: Set<String> = emptySet(),
        private val log: MutableList<String>? = null,
    ) : AudioFocusGuard.Store {
        var entries: Set<String> = initial

        override fun load(): Set<String> = entries

        override fun save(entries: Set<String>) {
            this.entries = entries
            log?.add("save $entries")
        }
    }

    private val music = "com.miui.player"

    /** 假手机上没设过时的模式，改回去也是它。 */
    private val usual = "foreground"
    private val video = "com.example.video"

    private fun guard(phone: Phone, store: Store = Store(log = phone.log)) =
        AudioFocusGuard(phone::shell, store, user = 0)

    @Test
    fun parsesAppOpsOutput() {
        val none = AudioFocusGuard.parse("No operations.\nDefault mode: allow\n")!!
        assertNull(none.packageMode)
        assertNull(none.uidMode)
        assertEquals("allow", none.defaultMode)

        val used =
            AudioFocusGuard.parse("TAKE_AUDIO_FOCUS: allow; time=+2d3h ago; duration=+1s\n")!!
        assertEquals("allow", used.packageMode)

        val both =
            AudioFocusGuard.parse(
                "Uid mode: TAKE_AUDIO_FOCUS: ignore\nTAKE_AUDIO_FOCUS: allow; time=+5m ago\n",
            )!!
        assertEquals("ignore", both.uidMode)
        assertEquals("allow", both.packageMode)

        assertNull(AudioFocusGuard.parse(""))
        assertNull(AudioFocusGuard.parse("Error: No UID for x.y in user 0"))
    }

    @Test
    fun onlyDefaultModeIsChanged() {
        fun original(out: String) = AudioFocusGuard.originalMode(AudioFocusGuard.parse(out))
        assertEquals("allow", original("No operations.\nDefault mode: allow"))
        assertEquals("foreground", original("No operations.\nDefault mode: foreground"))
        assertEquals("allow", original("TAKE_AUDIO_FOCUS: allow; time=+1m ago"))
        assertEquals("foreground", original("TAKE_AUDIO_FOCUS: foreground; time=+6d4h ago"))
        // 不知道默认模式就不改：改回时不知道写什么
        assertNull(original("No operations."))
        // 单独设过的不碰：包括 default（MODE_DEFAULT，对音频焦点等于不允许）和 uid 级的
        for (mode in listOf("ignore", "deny", "default")) assertNull(
            mode,
            original("TAKE_AUDIO_FOCUS: $mode"),
        )
        assertNull(original("Uid mode: TAKE_AUDIO_FOCUS: ignore"))
        assertNull(original("Uid mode: TAKE_AUDIO_FOCUS: allow\nTAKE_AUDIO_FOCUS: allow"))
        assertNull(original("something went wrong"))
    }

    @Test
    fun muteRecordsBeforeChangingAndRestoresOnMoveToMain() {
        val phone = Phone()
        phone.modes[music] = "foreground"
        val store = Store(log = phone.log)
        val guard = guard(phone, store)
        assertTrue(guard.mute(music, display = 7) { "com.miui.home" })
        assertEquals("ignore", phone.mode(music))
        assertEquals(setOf("$music=foreground"), store.entries)
        // 记录先落盘，再改 appops
        val saved = phone.log.indexOfFirst { it.startsWith("save") }
        val set = phone.log.indexOfFirst { it.contains(" set ") }
        assertTrue(phone.log.toString(), saved in 0 until set)

        guard.movedToMain(music)
        assertEquals("foreground", phone.mode(music))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun olderSystemsGetAllowBack() {
        val phone = Phone(defaultMode = "allow")
        val store = Store()
        val guard = guard(phone, store)
        assertTrue(guard.mute(music, 7) { null })
        assertEquals(setOf("$music=allow"), store.entries)
        guard.screenGone(7)
        assertEquals("allow", phone.modes[music])
    }

    @Test
    fun appInFrontOfTheUserIsNotChanged() {
        val phone = Phone()
        val store = Store()
        assertFalse(guard(phone, store).mute(music, display = 7) { music })
        assertNull(phone.modes[music])
        assertTrue(store.entries.isEmpty())
        assertTrue(phone.log.none { it.contains(" set ") })
    }

    @Test
    fun explicitSettingsAreLeftAlone() {
        val phone = Phone()
        phone.modes[music] = "deny"
        phone.uidModes[video] = "ignore"
        val store = Store()
        val guard = guard(phone, store)
        assertFalse(guard.mute(music, 7) { null })
        assertFalse(guard.mute(video, 7) { null })
        assertEquals("deny", phone.modes[music])
        assertNull(phone.modes[video])
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun alreadyMutedByUsJustAddsTheScreen() {
        val phone = Phone()
        val guard = guard(phone)
        assertTrue(guard.mute(music, 7) { null })
        val commands = phone.log.size
        assertTrue(guard.mute(music, 8) { error("不该再问前台") })
        assertEquals(commands, phone.log.size)
    }

    @Test
    fun restoredWhenTheLastScreenThatOpenedItCloses() {
        val phone = Phone()
        val store = Store()
        val guard = guard(phone, store)
        guard.mute(music, 7) { null }
        guard.mute(video, 8) { null }
        guard.mute(music, 8) { null }

        guard.screenGone(7)
        assertEquals("ignore", phone.mode(music))
        assertEquals("ignore", phone.mode(video))

        guard.screenGone(8)
        assertEquals(usual, phone.mode(music))
        assertEquals(usual, phone.mode(video))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun screenClosingOnlyRestoresItsOwnApps() {
        val phone = Phone()
        val guard = guard(phone)
        guard.mute(music, 7) { null }
        guard.mute(video, 8) { null }
        guard.screenGone(7)
        assertEquals(usual, phone.mode(music))
        assertEquals("ignore", phone.mode(video))
    }

    @Test
    fun leftoversFromAKilledProcessAreRestoredOnStart() {
        val phone = Phone()
        val store = Store()
        guard(phone, store).mute(music, 7) { null }
        // 进程被杀：内存里的东西没了，手机上还是 ignore，设置里还有记录
        val next = guard(phone, store)
        next.restoreLeftovers()
        assertEquals(usual, phone.mode(music))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun leftoversAreRestoredWhenAScreenIsCreatedOrClosed() {
        val phone = Phone()
        phone.modes[music] = "ignore"
        phone.modes[video] = "ignore"
        val store = Store(setOf("$music=foreground", "$video=foreground"))
        val guard = guard(phone, store)
        // 新开的屏要了 video：它不算留下来的
        guard.mute(video, 9) { null }
        guard.restoreLeftovers()
        assertEquals("foreground", phone.mode(music))
        assertEquals("ignore", phone.mode(video))
        assertEquals(setOf("$video=foreground"), store.entries)
    }

    @Test
    fun restoreWritesTheOriginalModeNotDefault() {
        val phone = Phone()
        phone.modes[music] = "ignore"
        phone.modes[video] = "ignore"
        // 记成 default（写回去应用就拿不到焦点）的按 allow 写回；deny 照原样
        val store = Store(setOf("$music=default", "$video=deny"))
        guard(phone, store).restoreLeftovers()
        assertEquals("allow", phone.mode(music))
        assertEquals("deny", phone.mode(video))
    }

    @Test
    fun changedByOthersMeanwhileIsNotOverwritten() {
        val phone = Phone()
        val store = Store()
        val guard = guard(phone, store)
        guard.mute(music, 7) { null }
        phone.modes[music] = "deny"
        guard.screenGone(7)
        assertEquals("deny", phone.mode(music))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun failedRestoreKeepsTheRecordForLater() {
        val phone = Phone()
        val store = Store()
        val guard = guard(phone, store)
        guard.mute(music, 7) { null }
        phone.rootWorks = false
        guard.screenGone(7)
        assertEquals("ignore", phone.mode(music))
        assertEquals(setOf("$music=foreground"), store.entries)

        phone.rootWorks = true
        guard.restoreLeftovers()
        assertEquals(usual, phone.mode(music))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun failedMuteLeavesNothingBehind() {
        val phone = Phone()
        phone.setWorks = false
        val store = Store()
        assertFalse(guard(phone, store).mute(music, 7) { null })
        assertNull(phone.modes[music])
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun uninstalledAppsAreForgotten() {
        val phone = Phone()
        val store = Store()
        val guard = guard(phone, store)
        guard.mute(music, 7) { null }
        phone.installed -= music
        guard.screenGone(7)
        assertTrue(store.entries.isEmpty())
        assertEquals(
            AudioFocusGuard.Restore.LATER,
            AudioFocusGuard.restoreAction(CommandResult(1, "", "su: not found")),
        )
    }

    @Test
    fun releaseAndDevBuildsKeepTheirOwnRecords() {
        val phone = Phone()
        val releaseStore = Store()
        val devStore = Store()
        val release = guard(phone, releaseStore)
        val dev = guard(phone, devStore)
        release.mute(music, 7) { null }
        // 测试版看到的是正式版改过的 ignore：不是默认模式，不记、不改
        assertFalse(dev.mute(music, 12) { null })
        assertTrue(devStore.entries.isEmpty())
        dev.restoreLeftovers()
        dev.screenGone(12)
        assertEquals("ignore", phone.mode(music))
        release.screenGone(7)
        assertEquals(usual, phone.mode(music))
    }

    @Test
    fun windowOnTheMainScreenRestores() {
        val phone = Phone()
        val store = Store()
        val guard = guard(phone, store)
        guard.mute(music, 7) { null }
        // 后台屏幕上的窗口、没禁过的包：什么也不做
        guard.onWindowShown(music, 7) { 0 }
        guard.onWindowShown(video, 0) { 0 }
        guard.onWindowShown(null, 0) { 0 }
        Thread.sleep(100)
        assertEquals("ignore", phone.mode(music))

        guard.onWindowShown(music, 0) { 0 }
        val deadline = System.currentTimeMillis() + 5_000
        while (store.entries.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(usual, phone.mode(music))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun recordsRoundTripAndBadEntriesAreDropped() {
        val records = mapOf(music to "allow", video to "deny")
        assertEquals(records, AudioFocusGuard.decode(AudioFocusGuard.encode(records)))
        val decoded =
            AudioFocusGuard.decode(
                setOf("bad name=allow", "x=allow", "com.a.b=", "com.c.d=weird", "com.e.f=ignore"),
            )
        assertEquals(
            mapOf("com.a.b" to "allow", "com.c.d" to "allow", "com.e.f" to "allow"),
            decoded,
        )
    }

    @Test
    fun badPackageNamesRunNothing() {
        val phone = Phone()
        assertFalse(guard(phone).mute("x; reboot", 7) { null })
        assertTrue(phone.log.isEmpty())
    }
}
