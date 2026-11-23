package com.dzshzx.zeta.core.update

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.dzshzx.zeta.i18n.Strings
import java.io.File
import java.security.MessageDigest

/**
 * 一个 APK 的身份：包名、versionCode、签名证书。证书都是 SHA-256（小写十六进制），和 release/signing-certs.txt 的写法一样。 [signers]
 * 是现在签它的证书；[history] 是签名 lineage 里从旧到新的全部证书（APK 签名方案 v3 的轮换），没轮换过时就是 [signers]。
 */
internal data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: List<String>,
    val history: List<String>,
)

/** 下载的 APK 装之前的核对（大小和 sha256 在下载时已对过清单，见 [UpdateHttp.download]）。 */
internal object ApkCheck {
    /**
     * 能装返回 null，否则是原因。[archive] 是从下载的文件里读出来的（读不出为 null），[installed] 是本机已装的这个包。 签名：已装的包现在的每张证书都要在新包的
     * lineage 里，也就是新包和它同一个签名，或者是从它轮换过来的 （v0.7.0 及更早是调试证书签的，v0.7.1 起的正式版带着「调试证书 → 正式发版证书」的
     * lineage）。最后由系统的签名校验把关。
     */
    fun verdict(archive: ApkFacts?, manifest: UpdateManifest, installed: ApkFacts): String? =
        when {
            archive == null -> Strings.updateApkUnreadable()
            archive.packageName != installed.packageName ->
                Strings.updateApkWrongPackage(archive.packageName, installed.packageName)
            archive.versionCode != manifest.versionCode.toLong() ->
                Strings.updateApkWrongCode(
                    archive.versionCode.toString(),
                    manifest.versionCode.toString(),
                )
            installed.signers.isEmpty() || !archive.history.containsAll(installed.signers) ->
                Strings.updateApkWrongSigner()
            else -> null
        }

    /** 读下载的 APK 的身份；不是有效的 APK 时为 null。 */
    fun readArchive(pm: PackageManager, file: File): ApkFacts? = runCatching {
        val info =
            pm.getPackageArchiveInfo(
                file.path,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            ) ?: return null
        facts(info.packageName, info.longVersionCode, info.signingInfo ?: return null)
    }
        .getOrNull()

    /** 本机已装的 [packageName] 的身份。 */
    fun readInstalled(pm: PackageManager, packageName: String): ApkFacts? = runCatching {
        val info =
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
        facts(info.packageName, info.longVersionCode, info.signingInfo ?: return null)
    }
        .getOrNull()

    private fun facts(packageName: String, versionCode: Long, signing: SigningInfo): ApkFacts {
        val signers = signing.apkContentsSigners.map(::sha256)
        // 多个签名者的包没有 lineage（系统不许它们轮换）
        val history =
            if (signing.hasMultipleSigners()) signers
            else signing.signingCertificateHistory.map(::sha256)
        return ApkFacts(packageName, versionCode, signers, history.ifEmpty { signers })
    }

    fun sha256(signature: Signature): String =
        UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
}
