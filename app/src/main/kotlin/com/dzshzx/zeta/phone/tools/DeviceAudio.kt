package com.dzshzx.zeta.phone.tools

import java.util.Locale
import kotlin.math.roundToInt

/** media：各路音量、响铃模式、音频输出、正在播放的内容，以及媒体按键。 */
internal object DeviceAudio {
    /** 工具里的名字 → AudioManager.STREAM_*。 */
    val STREAMS = linkedMapOf("media" to 3, "ring" to 2, "notification" to 5, "alarm" to 4, "call" to 0, "system" to 1)

    val STREAM_NAMES = mapOf("media" to "媒体", "ring" to "铃声", "notification" to "通知", "alarm" to "闹钟", "call" to "通话", "system" to "系统")

    /** 工具里的媒体动作 → `cmd media_session dispatch` 的键名。 */
    val KEYS = linkedMapOf(
        "play" to "play", "pause" to "pause", "play_pause" to "play-pause", "next" to "next", "previous" to "previous", "stop" to "stop",
    )

    val KEY_NAMES = mapOf("play" to "播放", "pause" to "暂停播放", "play_pause" to "播放或暂停", "next" to "下一首", "previous" to "上一首", "stop" to "停止播放")

    /** 各路音量那几行，由 [parseVolumes] 解读。 */
    const val VOLUMES_SCRIPT = "dumpsys audio | grep -E '^- STREAM_|^   Muted: |^   Min: |^   Max: |^   streamVolume:'"

    const val STATUS_SCRIPT =
        "echo '##volumes'; $VOLUMES_SCRIPT; " +
            "echo '##ringer'; settings get global mode_ringer; " +
            "echo '##output'; cmd audio get-current-output-device 2>&1 | head -1; " +
            "echo '##sessions'; dumpsys media_session"

    class Volume(val stream: Int, val level: Int, val min: Int, val max: Int, val muted: Boolean) {
        val percent: Int get() = if (max <= min) 0 else ((level - min) * 100.0 / (max - min)).roundToInt()
    }

    /** `dumpsys audio` 里各路音量的几行（见 [STATUS_SCRIPT]）→ 流编号 → 音量。 */
    fun parseVolumes(text: String): Map<Int, Volume> {
        val result = LinkedHashMap<Int, Volume>()
        var stream: Int? = null
        var muted = false
        var min = 0
        var max = 0
        for (line in text.lineSequence()) {
            val header = STREAM_HEADER.find(line)
            when {
                header != null -> {
                    stream = STREAM_IDS[header.groupValues[1]]
                    muted = false; min = 0; max = 0
                }
                line.trim().startsWith("Muted:") -> muted = line.substringAfter(':').trim() == "true"
                line.trim().startsWith("Min:") -> min = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.trim().startsWith("Max:") -> max = line.substringAfter(':').trim().toIntOrNull() ?: 0
                line.trim().startsWith("streamVolume:") -> {
                    val level = line.substringAfter(':').trim().toIntOrNull()
                    val s = stream
                    if (s != null && level != null && s !in result) result[s] = Volume(s, level, min, max, muted)
                }
            }
        }
        return result
    }

    /** 媒体会话：包名、播放状态、标题等（`dumpsys media_session`）。 */
    class Session(val packageName: String, val active: Boolean, val state: String?, val metadata: String?)

    fun parseSessions(text: String): List<Session> {
        val sessions = ArrayList<Session>()
        var pkg: String? = null
        var active = false
        var state: String? = null
        var metadata: String? = null
        fun flush() {
            val p = pkg ?: return
            if (state != null || metadata != null) sessions += Session(p, active, state, metadata)
            pkg = null
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("package=") -> { flush(); pkg = line.removePrefix("package="); active = false; state = null; metadata = null }
                pkg == null -> Unit
                line.startsWith("active=") -> active = line == "active=true"
                line.startsWith("state=") -> state = playbackState(line.removePrefix("state="))
                line.startsWith("metadata:") -> metadata = metadataText(line.removePrefix("metadata:").trim())
            }
        }
        flush()
        // 同一个会话会在「全局优先」和「会话栈」里各出现一次
        return sessions.distinctBy { listOf(it.packageName, it.state, it.metadata) }
    }

    /** `PlaybackState {state=3, position=…}` 或 `PlaybackState {state=PLAYING(3), …}` → playing 等。 */
    fun playbackState(value: String): String? {
        if (value == "null") return null
        val m = PLAYBACK.find(value) ?: return null
        val code = m.groupValues[2].toIntOrNull()
        return PLAYBACK_NAMES[code] ?: m.groupValues[1].takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT) ?: "state $code"
    }

    /** `size=7, description=标题, 歌手, 专辑` → `标题 — 歌手 — 专辑`（去掉 null）。 */
    fun metadataText(value: String): String? {
        if (value == "null" || value.isBlank()) return null
        val description = value.substringAfter("description=", value)
        val parts = description.split(", ").map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
        return parts.joinToString(" — ").takeIf { it.isNotEmpty() }
    }

    fun ringerName(value: String): String? = when (value.trim()) { "0" -> "silent"; "1" -> "vibrate"; "2" -> "normal"; else -> null }

    /** 状态全文。 */
    fun status(sections: Map<String, String>): String = buildString {
        val volumes = parseVolumes(sections["volumes"].orEmpty())
        append("volumes:")
        for ((name, id) in STREAMS) {
            val v = volumes[id] ?: continue
            append("\n  ").append(name).append(": ").append(v.level).append('/').append(v.max)
                .append(" (").append(v.percent).append("%)")
            if (v.muted) append(" muted")
        }
        ringerName(sections["ringer"].orEmpty())?.let { append("\nringer_mode: ").append(it) }
        sections["output"]?.trim()?.takeIf { it.isNotEmpty() && !it.contains("Exception") }?.let { append("\noutput: ").append(it) }
        val sessions = parseSessions(sections["sessions"].orEmpty())
        if (sessions.isEmpty()) append("\nmedia: no media session")
        else {
            append("\nmedia sessions (playing first):")
            for (s in sessions.sortedByDescending { it.state == "playing" }) {
                append("\n  ").append(s.packageName).append(": ").append(s.state ?: "no state")
                s.metadata?.let { append(" — ").append(it) }
            }
        }
    }

    /** 百分比 → 音量档位。 */
    fun levelFor(percent: Int, min: Int, max: Int): Int =
        (min + (max - min) * percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(min, max)

    private val STREAM_HEADER = Regex("^- (STREAM_[A-Z_]+)")
    private val STREAM_IDS = mapOf(
        "STREAM_VOICE_CALL" to 0, "STREAM_SYSTEM" to 1, "STREAM_RING" to 2, "STREAM_MUSIC" to 3,
        "STREAM_ALARM" to 4, "STREAM_NOTIFICATION" to 5,
    )
    private val PLAYBACK = Regex("state=(?:([A-Z_]+)\\()?(-?\\d+)")
    private val PLAYBACK_NAMES = mapOf(
        0 to "none", 1 to "stopped", 2 to "paused", 3 to "playing", 4 to "fast-forwarding", 5 to "rewinding",
        6 to "buffering", 7 to "error", 8 to "connecting", 9 to "skipping to previous", 10 to "skipping to next",
    )
}
