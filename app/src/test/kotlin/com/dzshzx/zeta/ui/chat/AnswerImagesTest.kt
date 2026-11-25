package com.dzshzx.zeta.ui.chat

import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.RemoteImage
import com.dzshzx.zeta.core.SessionImage
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.WorkItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 回答里的图片怎么从正文里取出、怎么分组：只隔空白的连成一组，被正文隔开的各成一组。 */
class AnswerImagesTest {
    private fun split(text: String) =
        answerImages(text, cwd = "/workspace/demo", home = "/home/zeta")

    private fun paths(groups: List<List<ImageRef>>) = groups.map { group ->
        group.map {
            when (it) {
                is FileImage -> it.path
                is RemoteImage -> it.url
                else -> it.key
            }
        }
    }

    @Test
    fun consecutiveImagesFormOneGroup() {
        val parts =
            split(
                "截了三张图：\n\n![首页](/tmp/a.png)\n![设置](shots/b.png)\n\n" +
                    "![网络](https://example.invalid/c.jpg)  \n",
            )
        assertEquals(
            listOf(
                listOf(
                    "/tmp/a.png",
                    "/workspace/demo/shots/b.png",
                    "https://example.invalid/c.jpg",
                ),
            ),
            paths(parts.groups),
        )
        assertEquals("截了三张图：", parts.text)
    }

    @Test
    fun singleImageIsAGroupOfOne() {
        val parts = split("改好了，效果如下：\n\n![](~/out.png)\n\n颜色换成了蓝色。")
        assertEquals(listOf(listOf("/home/zeta/out.png")), paths(parts.groups))
        assertEquals("改好了，效果如下：\n\n\n\n颜色换成了蓝色。", parts.text)
    }

    @Test
    fun imagesSeparatedByProseFormSeparateGroups() {
        val parts =
            split(
                "改之前：\n![](/tmp/before1.png)\n![](/tmp/before2.png)\n改之后：\n![](/tmp/after.png)",
            )
        assertEquals(
            listOf(listOf("/tmp/before1.png", "/tmp/before2.png"), listOf("/tmp/after.png")),
            paths(parts.groups),
        )
    }

    /** 重复的图只显示一次，不打断连着的一组；代码块里的写法不是图，留在正文里、把前后隔开。 */
    @Test
    fun duplicatesAndCodeBlocks() {
        val parts =
            split(
                "![](/tmp/a.png)\n![](/tmp/a.png)\n![](/tmp/b.png)\n" +
                    "```\n![](/tmp/code.png)\n```\n![](/tmp/c.png)",
            )
        assertEquals(
            listOf(listOf("/tmp/a.png", "/tmp/b.png"), listOf("/tmp/c.png")),
            paths(parts.groups),
        )
        assertEquals("```\n![](/tmp/code.png)\n```", parts.text.trim())
    }

    @Test
    fun noImagesLeavesTextAlone() {
        val text = "没有图片，只有 [链接](https://example.invalid)。"
        val parts = split(text)
        assertEquals(text, parts.text)
        assertEquals(emptyList<List<ImageRef>>(), parts.groups)
    }

    private fun read(id: String, path: String, vararg images: ImageRef) =
        ToolStep(
            id = id,
            name = "Read",
            input = buildJsonObject { put("file_path", path) },
            status = ToolStatus.Success,
            result = null,
            images = images.toList(),
        )

    @Test
    fun readImageIsTheLatestReadOfThatPath() {
        val first = SessionImage("s.jsonl", "1")
        val latest = SessionImage("s.jsonl", "2")
        val items =
            listOf<ChatItem>(
                WorkItem("w1", listOf(read("a", "/tmp/shot.png", first))),
                WorkItem(
                    "w2",
                    listOf(
                        read("b", "/tmp/shot.png", latest),
                        // 读别的文件、读了但结果里没图的不算
                        read("c", "/tmp/other.png", SessionImage("s.jsonl", "3")),
                        read("d", "/tmp/shot.png"),
                    ),
                ),
            )
        assertSame(latest, readImage(items, "/tmp/shot.png"))
        assertNull(readImage(items, "/tmp/never.png"))
        assertNull(readImage(emptyList(), "/tmp/shot.png"))
    }
}
