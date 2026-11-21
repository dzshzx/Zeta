package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmlTextTest {
    @Test
    fun unescapesEntitiesAndCharacterReferences() {
        assertEquals(
            "a & b < c > d \" e ' f",
            XmlText.unescape("a &amp; b &lt; c &gt; d &quot; e &apos; f"),
        )
        // 表情、生僻字在 BMP 之外：按码位还原成一对代理，不截成一个 char
        assertEquals("😀 😀 𠀀", XmlText.unescape("&#128512; &#x1F600; &#131072;"))
        assertEquals("it's\n", XmlText.unescape("it&#39;s&#10;"))
        // 一遍还原：&amp;lt; 是字面的 &lt;
        assertEquals("&lt; &#65;", XmlText.unescape("&amp;lt; &amp;#65;"))
        assertEquals("a b", XmlText.unescape("a&nbsp;b"))
        // 认不出的、超出 Unicode 范围的原样留着
        assertEquals(
            "&copy; &#99999999; &#xZZ; & x",
            XmlText.unescape("&copy; &#99999999; &#xZZ; & x"),
        )
    }

    @Test
    fun readsSharedPrefsStrings() {
        val xml =
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="clipboard_cipher_list">W3sidGltZSI6MX1d&#10;</string>
                <string name="a.b">x &amp; &#128512;</string>
                <string name="empty"></string>
                <int name="count" value="3" />
            </map>
            """
                .trimIndent()
        assertEquals("W3sidGltZSI6MX1d\n", XmlText.string(xml, "clipboard_cipher_list"))
        assertEquals("x & 😀", XmlText.string(xml, "a.b"))
        // 名字里的 . 按字面比，不当通配
        assertNull(XmlText.string(xml, "aXb"))
        assertEquals("", XmlText.string(xml, "empty"))
        assertNull(XmlText.string(xml, "count"))
        assertNull(XmlText.string(xml, "missing"))
    }
}
