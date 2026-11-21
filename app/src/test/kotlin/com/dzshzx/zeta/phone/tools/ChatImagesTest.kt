package com.dzshzx.zeta.phone.tools

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 路径、哈希、文件内容都是编造的。 */
class ChatImagesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!

    private fun request(source: String, vararg pairs: Pair<String, Any>) =
        PersonalQueries.searchRequest(
            buildJsonObject {
                put("source", source)
                for ((k, v) in pairs) if (v is Number) put(k, v) else put(k, v.toString())
            },
            now,
            zone,
        )

    private val wechatDir =
        "/data/user/0/com.tencent.mm/MicroMsg/00112233445566778899aabbccddeeff/image2/ab/cd"
    private val md5 = "abcd0123456789ef0123456789abcdef"
    private val qqBase = "/data/media/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

    @Test
    fun parsesFindLinesToTheMillisecond() {
        assertEquals(
            Triple(1795102062290L, 8639L, "$wechatDir/th_$md5"),
            ChatImages.parseLine("1795102062.290211999 8639 $wechatDir/th_$md5"),
        )
        assertEquals(
            Triple(1795102062000L, 1L, "/a b/c"),
            ChatImages.parseLine("1795102062 1 /a b/c"),
        )
        assertEquals(Triple(1795102062500L, 1L, "/x"), ChatImages.parseLine("1795102062.5 1 /x"))
        assertNull(ChatImages.parseLine("ZETA_NO_CACHE"))
        assertNull(ChatImages.parseLine(""))
    }

    @Test
    fun recognisesWechatFiles() {
        fun variant(name: String) =
            ChatImages.file(ChatImages.WECHAT, "$wechatDir/$name", 1, 1)?.variant
        assertEquals(ChatVariant.Thumb, variant("th_$md5"))
        assertEquals(ChatVariant.ThumbHd, variant("th_${md5}hd"))
        assertEquals(ChatVariant.Original, variant("$md5.jpg"))
        assertEquals(ChatVariant.Original, variant("$md5.png"))
        // 它自己的引用记录、半截文件不算
        assertNull(variant("th_${md5}hd⌖"))
        assertNull(variant(md5))
        assertNull(
            ChatImages.file(
                ChatImages.WECHAT,
                "$wechatDir/../.ref/d/1f2e3d4c-0000-4000-8000-000000000000",
                1,
                1,
            ),
        )
    }

    @Test
    fun groupsWechatFilesByMd5AndRoundTripsTheId() {
        val other = "0000ffff0000ffff0000ffff0000ffff"
        val files =
            listOf(
                ChatImages.file(
                    ChatImages.WECHAT,
                    "$wechatDir/th_$md5",
                    ms("2026-11-19T10:00"),
                    9_000,
                )!!,
                ChatImages.file(
                    ChatImages.WECHAT,
                    "$wechatDir/$md5.jpg",
                    ms("2026-11-19T12:00"),
                    1_300_000,
                )!!,
                ChatImages.file(
                    ChatImages.WECHAT,
                    "$wechatDir/th_${md5}hd",
                    ms("2026-11-19T10:00"),
                    40_000,
                )!!,
                ChatImages.file(
                    ChatImages.WECHAT,
                    "/data/user/0/com.tencent.mm/MicroMsg/x/image2/00/00/th_$other",
                    ms("2026-11-19T11:00"),
                    7_000,
                )!!,
            )
        val images = ChatImages.group(ChatImages.WECHAT, files)
        assertEquals(2, images.size)
        val first = images[0]
        assertEquals(ChatImages.wechatId(md5), first.id)
        // 时间取最新的文件（点开原图的时候），文件从清楚到模糊排
        assertEquals(ms("2026-11-19T12:00"), first.time)
        assertEquals(
            listOf(ChatVariant.Original, ChatVariant.ThumbHd, ChatVariant.Thumb),
            first.files.map { it.variant },
        )
        // 编号还原出 md5 的前 13 位，前四位就是子目录；开头是 0 的也不丢
        assertEquals(md5.take(13), ChatImages.wechatPrefix(first.id))
        assertEquals(other.take(13), ChatImages.wechatPrefix(images[1].id))
        assertTrue(first.id < (1L shl 53))
    }

    @Test
    fun qqIdsCarryTheirFolder() {
        fun file(path: String) = ChatImages.file(ChatImages.QQ, path, 1, 1)
        assertEquals(
            ChatVariant.Original,
            file("$qqBase/chatraw/7af/Cache_5881b02d03e177af")?.variant,
        )
        assertEquals(
            ChatVariant.Large,
            file("$qqBase/chatimg/0b6/Cache_-60a3eb76967dc0b6")?.variant,
        )
        assertEquals(ChatVariant.ThumbHd, file("$qqBase/chatthumb/e56/Cache_1234e56_hd")?.variant)
        assertEquals(ChatVariant.Thumb, file("$qqBase/chatthumb/e56/Cache_1234e56")?.variant)
        assertNull(file("$qqBase/Temp/abc/Cache_1"))

        val a = ChatImages.qqId("$qqBase/chatimg/0b6/Cache_-60a3eb76967dc0b6")!!
        val b = ChatImages.qqId("$qqBase/chatthumb/0b6/Cache_-60a3eb76967dc0b6")!!
        assertEquals("0b6", ChatImages.qqDir(a))
        assertEquals("0b6", ChatImages.qqDir(b))
        assertNotEquals(a, b)
        assertTrue(a in 1 until (1L shl 52))
        // 与挂在哪个根目录无关，只看 chatpic 之后
        assertEquals(
            a,
            ChatImages.qqId(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/chatimg/0b6/Cache_-60a3eb76967dc0b6",
            ),
        )
    }

    @Test
    fun formatsListsAndOneImage() {
        val files =
            listOf(
                ChatImages.file(
                    ChatImages.QQ,
                    "$qqBase/chatimg/047/Cache_1b4e5b4c60dc5047",
                    ms("2026-11-18T17:51"),
                    133_654,
                )!!,
                ChatImages.file(
                    ChatImages.QQ,
                    "$qqBase/chatthumb/7ca/Cache_9f7ca_hd",
                    ms("2026-11-18T17:51"),
                    24_937,
                )!!,
                ChatImages.file(
                    ChatImages.QQ,
                    "$qqBase/chatraw/502/Cache_-5a771d2db6233502",
                    ms("2026-11-17T19:02"),
                    642_157,
                )!!,
            )
        val images = ChatImages.group(ChatImages.QQ, files)
        val text =
            ChatImages.format(ChatImages.QQ, images, request("qq_images", "limit" to 2), zone, now)
                .lines()
        assertTrue(text[0], text[0].startsWith("QQ 图片 2 条（按缓存时间"))
        assertTrue(text[0], text[0].contains("view_media 加 kind: qq"))
        assertTrue(text[0], text[0].endsWith("还有更多，用 offset=2 继续"))
        assertEquals(
            setOf("大图 131KB", "高清缩略图 24KB"),
            text.drop(1).map { it.substringAfter("17:51 ") }.toSet(),
        )
        assertTrue(text[1], text[1].startsWith("#"))
        assertEquals(
            "没有符合条件的 QQ 图片。",
            ChatImages.format(ChatImages.QQ, emptyList(), request("qq_images"), zone, now),
        )

        val wechat =
            ChatImage(
                ChatImages.wechatId(md5),
                ms("2026-11-19T12:00"),
                listOf(
                    ChatFile(
                        "$wechatDir/$md5.jpg",
                        ms("2026-11-19T12:00"),
                        1_300_000,
                        ChatVariant.Original,
                    ),
                ),
            )
        assertEquals(
            "微信图片 #${wechat.id} 2026-11-19 12:00\n原图 1.2MB 2026-11-19 12:00 $wechatDir/$md5.jpg\n用 view_media 加 kind: wechat 看图",
            ChatImages.formatOne(ChatImages.WECHAT, wechat, zone),
        )
    }

    @Test
    fun keywordsAreRefused() {
        try {
            ChatImages.search(request("wechat_images", "query" to "猫"), zone, now)
            fail("query should be refused")
        } catch (e: BadInput) {
            assertTrue(e.message!!, e.message!!.startsWith("wechat_images 只能按时间查"))
        }
    }

    @Test
    fun sniffsFormats() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals("jpeg", ChatImages.sniff(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertEquals("png", ChatImages.sniff(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d)))
        assertEquals("gif", ChatImages.sniff("GIF89a".toByteArray()))
        assertEquals("webp", ChatImages.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("wxgf", ChatImages.sniff("wxgf".toByteArray() + bytes(0x13, 0)))
        assertNull(ChatImages.sniff(bytes(0, 0, 0, 0)))
        assertNull(ChatImages.sniff(ByteArray(0)))
    }

    @Test
    fun cutsTheColourStreamOutOfWxgf() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        val vps = bytes(0, 0, 0, 1, 0x40, 0x01)
        // 文件头：wxgf、长度、版本、宽 0x0fc0、高 0x0bd0，后面混进一个不是 VPS 的起始码
        val header =
            "wxgf".toByteArray() +
                bytes(0x13, 0x00, 0x02, 0x0f, 0xc0, 0x0b, 0xd0) +
                bytes(0, 0, 0, 1, 0xc0, 0x67)
        val colour = vps + bytes(0x0c, 0x01, 0xff, 0xff, 0, 0, 0, 1, 0x42, 0x01, 0x99)
        val alpha = vps + bytes(0x0c, 0x01, 0x77)
        val file = header + colour + alpha
        assertTrue(Wxgf.isWxgf(file))
        assertEquals(4032 to 3024, Wxgf.size(file))
        assertArrayEquals(colour, Wxgf.stream(file))
        // 没有透明通道时到文件尾
        assertArrayEquals(colour, Wxgf.stream(header + colour))
        assertNull(Wxgf.stream(header))
        assertNull(Wxgf.stream(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertNull(Wxgf.size("wxgf".toByteArray() + bytes(0x13, 0, 0x02, 0, 0, 0, 0)))
    }

    @Test
    fun registeredAsSources() {
        assertEquals(PersonalSource.WechatImages, PersonalSource.of("wechat_images"))
        assertEquals(PersonalSource.QqImages, PersonalSource.of("qq_images"))
        assertEquals(
            "看微信图片",
            PersonalTools.title("view_media", buildJsonObject { put("kind", "wechat") }),
        )
        assertEquals(
            "查看QQ 图片",
            PersonalTools.title("search_personal", buildJsonObject { put("source", "qq_images") }),
        )
    }
}
