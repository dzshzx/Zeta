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

/** 通知深链：页名是协议，改名前发出的通知还要打开到对的页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsLinkTest {
    @After
    fun clear() {
        SettingsLink.pending = null
    }

    private fun link(name: String) = Intent().putExtra(SettingsLink.EXTRA, name)

    @Test
    fun currentNameOpensThatPage() {
        val intent = link(SettingsSubPage.PermissionHealth.name)
        SettingsLink.accept(intent)
        assertEquals(SettingsSubPage.PermissionHealth, SettingsLink.pending)
        assertFalse(intent.hasExtra(SettingsLink.EXTRA))
    }

    @Test
    fun nameFromBeforeTheRenameStillOpensPermissionHealth() {
        SettingsLink.accept(link("Permissions"))
        assertEquals(SettingsSubPage.PermissionHealth, SettingsLink.pending)
    }

    @Test
    fun unknownNameOpensNothing() {
        SettingsLink.accept(link("NoSuchPage"))
        assertNull(SettingsLink.pending)
    }
}
