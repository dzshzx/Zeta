package com.dzshzx.zeta.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ControlSelectorTest {
    private fun select(match: String? = null, id: String? = null) = ControlSelector.of(match, id)!!

    @Test
    fun blankSelectorIsNone() {
        assertNull(ControlSelector.of(null, null))
        assertNull(ControlSelector.of("  ", " "))
        assertNotNull(ControlSelector.of("OK", null))
    }

    @Test
    fun textScoresExactThenPrefixThenContains() {
        val selector = select("wi-fi")
        assertEquals(3, selector.score(ControlFacts(text = "Wi-Fi")))
        assertEquals(2, selector.score(ControlFacts(text = "Wi-Fi settings")))
        assertEquals(1, selector.score(ControlFacts(description = "Turn on  Wi-Fi\n")))
        assertEquals(0, selector.score(ControlFacts(text = "Bluetooth")))
        // 空白合并、不分大小写；提示文字也算
        assertEquals(3, select("search  apps").score(ControlFacts(hint = " Search\tApps ")))
    }

    @Test
    fun idMatchesFullOrShortName() {
        val facts = ControlFacts(viewId = "com.android.settings:id/search_bar")
        assertEquals(1, select(id = "search_bar").score(facts))
        assertEquals(1, select(id = "com.android.settings:id/search_bar").score(facts))
        assertEquals(1, select(id = "other.app:id/search_bar").score(facts))
        assertEquals(0, select(id = "search").score(facts))
        assertEquals(0, select(id = "search_bar").score(ControlFacts(text = "search_bar")))
    }

    @Test
    fun textAndIdMustBothMatch() {
        val selector = select("OK", "button1")
        assertEquals(3, selector.score(ControlFacts(text = "OK", viewId = "android:id/button1")))
        assertEquals(0, selector.score(ControlFacts(text = "OK", viewId = "android:id/button2")))
        assertEquals(
            0,
            selector.score(ControlFacts(text = "Cancel", viewId = "android:id/button1")),
        )
    }

    @Test
    fun tapPrefersBetterMatchOverClickable() {
        val controls =
            listOf(
                ControlFacts(description = "Wi-Fi, connected", clickable = true),
                ControlFacts(text = "Wi-Fi"),
            )
        val pick = select("Wi-Fi").pick(controls, ControlPurpose.TAP)!!
        assertEquals(1, pick.index)
        assertEquals(1, pick.ties)
    }

    @Test
    fun tapPrefersClickableAmongEqualMatches() {
        val controls =
            listOf(
                ControlFacts(text = "Send"),
                ControlFacts(text = "Send", clickable = true),
                ControlFacts(text = "Send", clickable = true, enabled = false),
            )
        assertEquals(1, select("send").pick(controls, ControlPurpose.TAP)!!.index)
    }

    @Test
    fun typePrefersEditableOverExactLabel() {
        val controls =
            listOf(
                ControlFacts(text = "Name"),
                ControlFacts(hint = "Name (required)", editable = true),
            )
        assertEquals(1, select("name").pick(controls, ControlPurpose.TYPE)!!.index)
        assertEquals(0, select("name").pick(controls, ControlPurpose.TAP)!!.index)
    }

    @Test
    fun tiesAreCountedAndFirstWins() {
        val controls =
            listOf(
                ControlFacts(text = "Delete", clickable = true),
                ControlFacts(text = "Other", clickable = true),
                ControlFacts(text = "Delete", clickable = true),
            )
        val pick = select("Delete").pick(controls, ControlPurpose.TAP)!!
        assertEquals(0, pick.index)
        assertEquals(2, pick.ties)
    }

    @Test
    fun noMatchGivesNull() {
        assertNull(
            select("Missing").pick(listOf(ControlFacts(text = "Present")), ControlPurpose.ANY),
        )
    }

    @Test
    fun describeNamesWhatWasSought() {
        assertEquals("「OK」 id=button1", select("OK", "button1").describe())
        assertEquals("id=search", select(id = "search").describe())
    }
}
