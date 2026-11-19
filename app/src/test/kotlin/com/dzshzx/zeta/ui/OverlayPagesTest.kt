package com.dzshzx.zeta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPagesTest {
    private fun allOpen() = OverlayPages(settings = true, linux = true, install = true, login = true, phone = true, ssh = true, tasksScope = "k")

    @Test
    fun closeAllKeepsSetupWhenAsked() {
        val pages = allOpen()
        pages.closeAll(keepSetup = true)
        assertTrue(pages.install)
        assertTrue(pages.login)
        assertFalse(pages.settings || pages.linux || pages.phone || pages.ssh)
        assertNull(pages.tasksScope)

        pages.closeAll(keepSetup = false)
        assertFalse(pages.any)
    }

    @Test
    fun backClosesTopmostFirst() {
        val pages = allOpen()
        val order = mutableListOf<String>()
        while (pages.any) {
            val before = listOf(pages.login, pages.install, pages.tasksScope != null, pages.ssh, pages.phone, pages.linux, pages.settings)
            pages.closeTop()
            val after = listOf(pages.login, pages.install, pages.tasksScope != null, pages.ssh, pages.phone, pages.linux, pages.settings)
            order += listOf("login", "install", "tasks", "ssh", "phone", "linux", "settings")[before.indices.single { before[it] != after[it] }]
        }
        assertEquals(listOf("login", "install", "tasks", "ssh", "phone", "linux", "settings"), order)
    }
}
