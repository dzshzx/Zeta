package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SharedFoldersTest {
    @Test
    fun normalizeKeepsLexicalPath() {
        assertEquals("/sdcard/Download", SharedFolders.normalize(" /sdcard//./Download/ "))
        assertEquals("/sdcard", SharedFolders.normalize("/sdcard/Download/.."))
        assertNull(SharedFolders.normalize("relative/path"))
        assertNull(SharedFolders.normalize("/.."))
        assertNull(SharedFolders.normalize("/sdcard/a\nb"))
    }

    @Test
    fun internalStorageBindsToMediaDir() {
        assertEquals("/data/media/0/DCIM", SharedFolders.bindSource("/storage/emulated/0/DCIM"))
        assertEquals("/data/local/tmp/x", SharedFolders.bindSource("/data/local/tmp/x"))
    }

    /** 工作区本来就在 /workspace，Zeta 自带环境的目录也不能共享；别的目录可以。 */
    @Test
    fun workspaceAndOwnEnvironmentAreNotShared() {
        val rootfs = LinuxEnv.OWN_ROOTFS
        assertNotNull(SharedFolders.sourceProblem(LinuxEnv.WORKSPACE, emptyList(), rootfs))
        assertNotNull(SharedFolders.sourceProblem("${LinuxEnv.WORKSPACE}/claude", emptyList(), rootfs))
        assertNotNull(SharedFolders.sourceProblem("$rootfs/home", emptyList(), rootfs))
        assertNull(SharedFolders.sourceProblem("/data/local/tmp/eta", emptyList(), rootfs))
        assertNull(SharedFolders.sourceProblem("/sdcard/Download", emptyList(), rootfs))
    }

    @Test
    fun namesAreAsciiAndUnique() {
        val existing = listOf(SharedFolder("Download", "/sdcard/Download"))
        assertNull(SharedFolders.nameProblem("photos", existing))
        assertNotNull(SharedFolders.nameProblem("Download", existing))
        assertNotNull(SharedFolders.nameProblem("..", existing))
        assertNotNull(SharedFolders.nameProblem("中文", existing))
    }
}
