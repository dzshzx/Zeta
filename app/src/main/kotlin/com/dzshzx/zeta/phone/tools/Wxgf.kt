package com.dzshzx.zeta.phone.tools

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * 微信聊天原图的私有格式 wxgf：`wxgf` 开头的文件头，后面是标准的 HEVC（H.265）Annex-B 码流，一张图就是一帧。
 * 文件头第 7–10 字节是宽、高（大端 16 位）。带透明的图在后面再接一段码流（透明通道），从第二个 VPS 起，这里不要它。
 * 文件头里也可能碰巧出现 `00 00 00 01`，所以码流从第一个「起始码 + VPS」（`00 00 00 01 40 01`）算起。
 *
 * 转成普通图片在手机上做：码流交给系统的 HEVC 解码器（MediaCodec，先用软解 c2.android.hevc.decoder，小图硬解常不收），
 * 解出的 YUV 帧排成 NV21，用 YuvImage 压成 JPEG。动图（由 GIF 转来的多帧）只取第一帧。
 */
internal object Wxgf {
    private val MAGIC = byteArrayOf(0x77, 0x78, 0x67, 0x66) // "wxgf"
    private val VPS = byteArrayOf(0, 0, 0, 1, 0x40, 0x01)

    private const val SOFTWARE_DECODER = "c2.android.hevc.decoder"
    private const val TIMEOUT_MS = 15_000L

    fun isWxgf(bytes: ByteArray): Boolean = bytes.size >= 4 && (0 until 4).all { bytes[it] == MAGIC[it] }

    /** 文件头里记的宽高；看着不像就是 null。 */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (!isWxgf(bytes) || bytes.size < 11) return null
        val width = u16(bytes, 7)
        val height = u16(bytes, 9)
        return (width to height).takeIf { width in 1..16_384 && height in 1..16_384 }
    }

    /** 第一段 HEVC 码流：从第一个 VPS 起，到下一个 VPS（透明通道）前或文件尾。没有就是 null。 */
    fun stream(bytes: ByteArray): ByteArray? {
        if (!isWxgf(bytes)) return null
        val start = indexOf(bytes, VPS, 4).takeIf { it >= 0 } ?: return null
        val next = indexOf(bytes, VPS, start + VPS.size)
        return bytes.copyOfRange(start, if (next >= 0) next else bytes.size)
    }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..bytes.size - pattern.size) {
            for (j in pattern.indices) if (bytes[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    /** wxgf 转成 JPEG（原尺寸）。解不出来时抛出异常，message 说明原因。 */
    fun toJpeg(bytes: ByteArray, quality: Int = 90): ByteArray {
        val stream = stream(bytes) ?: throw IllegalStateException("wxgf 里没找到 HEVC 码流")
        val (width, height) = size(bytes) ?: (1920 to 1080)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, stream.size + 4096)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        val codec = runCatching { MediaCodec.createByCodecName(SOFTWARE_DECODER) }
            .getOrElse { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC) }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var queued = false
            var ended = false
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!ended) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        if (!queued) {
                            val buffer = codec.getInputBuffer(input) ?: throw IllegalStateException("解码器没给输入缓冲")
                            buffer.clear()
                            buffer.put(stream)
                            codec.queueInputBuffer(input, 0, stream.size, 0, 0)
                            queued = true
                        } else {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            ended = true
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    val image = if (info.size > 0) codec.getOutputImage(output) else null
                    val jpeg = image?.use { nv21Jpeg(it, quality) }
                    codec.releaseOutputBuffer(output, false)
                    if (jpeg != null) return jpeg
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            throw IllegalStateException("HEVC 解码器没解出画面（${codec.name}）")
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** 解码器给的 YUV_420_888 帧（按裁剪框）排成 NV21 再压成 JPEG。宽高取偶数，奇数时少一行或一列。 */
    private fun nv21Jpeg(image: Image, quality: Int): ByteArray {
        if (image.format != ImageFormat.YUV_420_888) throw IllegalStateException("解码器输出的格式不支持（${image.format}）")
        val crop = image.cropRect
        val width = crop.width() and 1.inv()
        val height = crop.height() and 1.inv()
        if (width < 2 || height < 2) throw IllegalStateException("解出的画面太小（${crop.width()}x${crop.height()}）")
        val (yPlane, uPlane, vPlane) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val nv21 = ByteArray(width * height * 3 / 2)
        val y = yPlane.buffer
        for (row in 0 until height) {
            val base = (crop.top + row) * yPlane.rowStride + crop.left * yPlane.pixelStride
            if (yPlane.pixelStride == 1) {
                y.position(base)
                y.get(nv21, row * width, width)
            } else {
                for (col in 0 until width) nv21[row * width + col] = y.get(base + col * yPlane.pixelStride)
            }
        }
        val u = uPlane.buffer
        val v = vPlane.buffer
        var at = width * height
        for (row in 0 until height / 2) {
            val uBase = (crop.top / 2 + row) * uPlane.rowStride + (crop.left / 2) * uPlane.pixelStride
            val vBase = (crop.top / 2 + row) * vPlane.rowStride + (crop.left / 2) * vPlane.pixelStride
            for (col in 0 until width / 2) {
                nv21[at++] = v.get(vBase + col * vPlane.pixelStride)
                nv21[at++] = u.get(uBase + col * uPlane.pixelStride)
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }
}
