package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.ui.setup.SharedFoldersScreen
import org.junit.Test

class SharedFolderShots : ShotTest() {
    /** COUI 对话框：共享文件夹页点“添加共享文件夹”弹出的那个。列子目录要 root，这里显示成“无法打开该目录”。 */
    @Test
    fun addDialog() {
        val hub = Fixtures.readyHub(app)
        shoot("shared-folder-add", prepare = { onNodeWithText("添加共享文件夹").performClick() }) {
            SharedFoldersScreen(hub = hub, onBack = {})
        }
    }
}
