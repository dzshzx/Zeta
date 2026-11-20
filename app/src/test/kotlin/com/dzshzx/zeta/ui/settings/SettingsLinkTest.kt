package com.dzshzx.zeta.ui.settings

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 通知深链：页名（[SettingsSubPage.link]）是协议，已经发出的通知还要打开到对的页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsLinkTest {
    @After
    fun clear() {
        SettingsLink.pending = null
    }

    private fun link(name: String) = Intent().putExtra(SettingsLink.EXTRA, name)

    @Test
    fun linksAreDistinct() {
        val links = SettingsSubPage.entries.map { it.link }
        assertEquals(links.size, links.toSet().size)
    }

    @Test
    fun eachLinkOpensItsPage() {
        for (page in SettingsSubPage.entries) {
            val intent = link(page.link)
            SettingsLink.accept(intent)
            assertEquals(page, SettingsLink.pending)
            assertFalse(intent.hasExtra(SettingsLink.EXTRA))
            SettingsLink.pending = null
        }
    }

    /** 0.7.0 发出的无障碍提醒带的是 Permissions。 */
    @Test
    fun permissionsOpensPermissionHealth() {
        SettingsLink.accept(link("Permissions"))
        assertEquals(SettingsSubPage.PermissionHealth, SettingsLink.pending)
    }

    @Test
    fun unknownNameOpensNothing() {
        SettingsLink.accept(link("NoSuchPage"))
        assertNull(SettingsLink.pending)
    }
}
