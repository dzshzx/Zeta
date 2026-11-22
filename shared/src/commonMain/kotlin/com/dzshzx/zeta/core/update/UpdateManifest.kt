package com.dzshzx.zeta.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 检查、下载、核对、安装里出的问题；[reason] 是给人看的一句话，界面照原样显示。 */
class UpdateException(val reason: String) : Exception(reason)

/** 清单里写的一个安装包：文件名（下载到本地也用它）、下载地址（GitHub 上的再走镜像，见 [Mirrors]）、字节数、SHA-256（小写十六进制）。 */
data class UpdatePackage(val file: String, val url: String, val size: Long, val sha256: String)

/**
 * 每次发版 Release 里的 `update.json`（tools/release 的 package 写的，字段见 docs/release.md「Release
 * 的资产」），手机和桌面版共用。 只取应用内更新用得到的字段，不认识的忽略：以后加字段只加不改。
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    /** 更新说明：上一个正式版以来的提交标题，Markdown 列表。 */
    val notes: String,
    /** 手机版 APK 的文件名，下载到本地也用它。 */
    val apk: String,
    /** APK 的下载地址。 */
    val url: String,
    val size: Long,
    /** APK 的 SHA-256，小写十六进制。 */
    val sha256: String,
    /** 打包的时间（RFC 3339），旧的清单没有时为空。 */
    val publishedAt: String = "",
    /** 桌面版的 MSI：`msi`、`msiUrl`、`msiSize`、`msiSha256` 四项都在且合规才有。v0.7.5 及更早的清单没有，手机不读它。 */
    val msi: UpdatePackage? = null,
) {
    /** 手机版的安装包。 */
    val apkPackage: UpdatePackage
        get() = UpdatePackage(apk, url, size, sha256)

    /** 比已装的 [installedCode] 新：versionCode 大才算，版本名不比。 */
    fun isNewerThan(installedCode: Int): Boolean = versionCode > installedCode

    companion object {
        /** 最新正式版的清单：GitHub 把 latest 跳到最新发布的那个 Release。手机正式版和桌面版都读它。 */
        const val LATEST_URL = "https://github.com/dzshzx/Zeta/releases/latest/download/update.json"

        /** 清单只有一两 KB，比这大的不当它是清单。 */
        const val MAX_BYTES = 256 * 1024

        /** APK 大小的上限：正式版约 13 MB，留足余量，挡住写错的清单把存储写满。 */
        private const val MAX_APK_BYTES = 512L * 1024 * 1024

        /** MSI 大小的上限：带着 Windows 的 JRE，约 115 MB。 */
        private const val MAX_MSI_BYTES = 2L * 1024 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }
        private val Hex64 = Regex("[0-9a-f]{64}")
        private val VersionName = Regex("""\d+\.\d+\.\d+""")
        private val ApkName = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk""")
        private val MsiName = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.msi""")

        /** 读 update.json 的文字；格式不对时抛 [UpdateException]，说哪一项不对。桌面版那几项不对时只是没有 [msi]，不算清单坏了。 */
        fun parse(text: String): UpdateManifest {
            val root =
                runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                    ?: throw bad("不是 JSON 对象")
            val fields = Fields(root)
            val versionCode =
                fields.number("versionCode")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                    ?: throw bad("versionCode 缺少或不是正整数")
            val versionName =
                fields.string("versionName")?.takeIf { VersionName.matches(it) }
                    ?: throw bad("versionName 缺少或不是 X.Y.Z")
            val apk =
                fields.string("apk")?.takeIf { ApkName.matches(it) }
                    ?: throw bad("apk 缺少或不是 .apk 文件名")
            val url = fields.string("url")?.takeIf(::isWebAddress) ?: throw bad("url 缺少或不是网址")
            val size =
                fields.number("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: throw bad("size 缺少或不对")
            val sha256 =
                fields.string("sha256")?.takeIf { Hex64.matches(it) }
                    ?: throw bad("sha256 缺少或不是 64 位小写十六进制")
            return UpdateManifest(
                versionCode = versionCode.toInt(),
                versionName = versionName,
                notes = fields.string("notes").orEmpty(),
                apk = apk,
                url = url,
                size = size,
                sha256 = sha256,
                publishedAt = fields.string("publishedAt").orEmpty(),
                msi = msi(fields),
            )
        }

        /** 桌面版的四项；缺一项或哪项不合规就是 null。 */
        private fun msi(fields: Fields): UpdatePackage? {
            val file = fields.string("msi")?.takeIf { MsiName.matches(it) } ?: return null
            val url = fields.string("msiUrl")?.takeIf(::isWebAddress) ?: return null
            val size = fields.number("msiSize")?.takeIf { it in 1..MAX_MSI_BYTES } ?: return null
            val sha256 = fields.string("msiSha256")?.takeIf { Hex64.matches(it) } ?: return null
            return UpdatePackage(file, url, size, sha256)
        }

        private fun isWebAddress(url: String) =
            url.startsWith("https://") || url.startsWith("http://")

        private fun bad(what: String) = UpdateException("更新清单格式不对：$what")
    }

    /** 按类型取清单里的一项：字符串只认 JSON 字符串，数字只认不带引号的整数。 */
    private class Fields(private val root: JsonObject) {
        fun string(key: String): String? =
            (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun number(key: String): Long? =
            (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    }
}
