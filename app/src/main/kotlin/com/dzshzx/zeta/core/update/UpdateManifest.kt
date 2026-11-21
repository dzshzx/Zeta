package com.dzshzx.zeta.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 检查、下载、核对、安装里出的问题；[reason] 是给人看的一句话，界面照原样显示。 */
internal class UpdateException(val reason: String) : Exception(reason)

/**
 * 每次发版 Release 里的 `update.json`（tools/release 的 package 写的，字段见 docs/release.md「Release 的资产」）。
 * 只取应用内更新用得到的字段，不认识的忽略：以后加字段只加不改。
 */
internal data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    /** 更新说明：上一个正式版以来的提交标题，Markdown 列表。 */
    val notes: String,
    /** APK 的文件名，下载到本地也用它。 */
    val apk: String,
    /** APK 的下载地址（GitHub 上的再走镜像，见 [Mirrors]）。 */
    val url: String,
    val size: Long,
    /** APK 的 SHA-256，小写十六进制。 */
    val sha256: String,
    /** 打包的时间（RFC 3339），旧的清单没有时为空。 */
    val publishedAt: String = "",
) {
    /** 比已装的 [installedCode] 新：versionCode 大才算，版本名不比。 */
    fun isNewerThan(installedCode: Int): Boolean = versionCode > installedCode

    companion object {
        /** 清单只有一两 KB，比这大的不当它是清单。 */
        const val MAX_BYTES = 256 * 1024

        /** APK 大小的上限：正式版约 13 MB，留足余量，挡住写错的清单把存储写满。 */
        private const val MAX_APK_BYTES = 512L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }
        private val Hex64 = Regex("[0-9a-f]{64}")
        private val VersionName = Regex("""\d+\.\d+\.\d+""")
        private val FileName = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk""")

        /** 读 update.json 的文字；格式不对时抛 [UpdateException]，说哪一项不对。 */
        fun parse(text: String): UpdateManifest {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: throw bad("不是 JSON 对象")
            fun string(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun number(key: String): Long? = (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val versionCode = number("versionCode")?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: throw bad("versionCode 缺少或不是正整数")
            val versionName = string("versionName")?.takeIf { VersionName.matches(it) } ?: throw bad("versionName 缺少或不是 X.Y.Z")
            val apk = string("apk")?.takeIf { FileName.matches(it) } ?: throw bad("apk 缺少或不是 .apk 文件名")
            val url = string("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: throw bad("url 缺少或不是网址")
            val size = number("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: throw bad("size 缺少或不对")
            val sha256 = string("sha256")?.takeIf { Hex64.matches(it) } ?: throw bad("sha256 缺少或不是 64 位小写十六进制")
            return UpdateManifest(
                versionCode = versionCode.toInt(),
                versionName = versionName,
                notes = string("notes").orEmpty(),
                apk = apk,
                url = url,
                size = size,
                sha256 = sha256,
                publishedAt = string("publishedAt").orEmpty(),
            )
        }

        private fun bad(what: String) = UpdateException("更新清单格式不对：$what")
    }
}
