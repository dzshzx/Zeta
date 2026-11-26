package com.dzshzx.zeta.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/** 超级岛和焦点卡片上的圆形应用图标 `ic_island_icon` 照抄启动图标的底和 ζ 两层（矢量图不能引用别的图）：正式版、测试版各一份，改了启动图标没跟着改时在这里失败。 */
class IslandIconTest {
    @Test
    fun islandIconCopiesTheLauncherLayers() {
        for (variant in listOf("main", "debug")) {
            val dir = "src/$variant/res/drawable"
            val launcher =
                layers("$dir/ic_launcher_background.xml") +
                    layers("$dir/ic_launcher_foreground.xml")
            val inner =
                root("$dir/ic_island_icon.xml")
                    .children()
                    .single { it.tagName == "group" }
                    .children()
                    .single { it.tagName == "group" }
            assertEquals(variant, launcher, inner.children().map(::describe))
        }
    }

    private fun root(path: String): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    private fun layers(path: String): List<String> = root(path).children().map(::describe)

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>()

    /** 元素的标签、各属性（按名字排）和子元素，写成一行，比较时不看排版。 */
    private fun describe(e: Element): String {
        val attrs =
            (0 until e.attributes.length)
                .map(e.attributes::item)
                .filter { it.nodeType == Node.ATTRIBUTE_NODE && !it.nodeName.startsWith("xmlns") }
                .map { "${it.nodeName}=${it.nodeValue}" }
                .sorted()
        return "<${e.tagName} ${attrs.joinToString(" ")}>" +
            e.children().joinToString("") { describe(it) }
    }
}
