package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioTest {
    // 真机 dumpsys audio 里各路音量的几行（见 STATUS_SCRIPT 的 grep）
    private val volumes =
        """
        - STREAM_VOICE_CALL:
           Muted: false
           Min: 1
           Max: 11
           streamVolume:11
        - STREAM_SYSTEM (aliased to: STREAM_RING):
           Muted: true
           Min: 0
           Max: 15
           streamVolume:0
        - STREAM_RING:
           Muted: true
           Min: 0
           Max: 15
           streamVolume:0
        - STREAM_MUSIC:
           Muted: false
           Min: 0
           Max: 150
           streamVolume:45
        - STREAM_ALARM:
           Muted: false
           Min: 1
           Max: 15
           streamVolume:12
        - STREAM_NOTIFICATION (aliased to: STREAM_RING):
           Muted: true
           Min: 0
           Max: 15
           streamVolume:0
        - STREAM_SYSTEM_ENFORCED (aliased to: STREAM_RING):
           Muted: false
           Min: 0
           Max: 7
           streamVolume:7
        """
            .trimIndent()

    @Test
    fun parsesVolumes() {
        val parsed = DeviceAudio.parseVolumes(volumes)
        assertEquals(45, parsed.getValue(3).level)
        assertEquals(150, parsed.getValue(3).max)
        assertEquals(30, parsed.getValue(3).percent)
        assertEquals(12, parsed.getValue(4).level)
        assertEquals(1, parsed.getValue(4).min)
        assertTrue(parsed.getValue(2).muted)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), parsed.keys)
    }

    @Test
    fun levelForPercent() {
        assertEquals(45, DeviceAudio.levelFor(30, 0, 150))
        assertEquals(1, DeviceAudio.levelFor(0, 1, 15))
        assertEquals(15, DeviceAudio.levelFor(100, 1, 15))
        assertEquals(8, DeviceAudio.levelFor(50, 1, 15))
    }

    // 按 AOSP MediaSessionRecord.dump 的格式写的样本
    private val sessions =
        """
        MEDIA SESSION SERVICE (dumpsys media_session)

        Global priority session is com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
          HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
            ownerPid=5199, ownerUid=1000, userId=0
            package=com.android.server.telecom
            active=false
            state=null
            metadata: null
        User Records:
          Sessions Stack - have 2 sessions:
            MusicService com.example.music/MusicService/12 (userId=0)
              ownerPid=100, ownerUid=10100, userId=0
              package=com.example.music
              active=true
              state=PlaybackState {state=3, position=12000, buffered position=0, speed=1.0, updated=1, actions=822, custom actions=[], active item id=-1, error=null}
              metadata: size=7, description=Song Title, Some Singer, An Album
            Video com.example.video/Video/3 (userId=0)
              package=com.example.video
              active=false
              state=PlaybackState {state=PAUSED(2), position=0}
              metadata: null
        """
            .trimIndent()

    @Test
    fun parsesSessions() {
        val parsed = DeviceAudio.parseSessions(sessions)
        assertEquals(
            listOf("com.example.music", "com.example.video"),
            parsed.map { it.packageName },
        )
        assertEquals("playing", parsed[0].state)
        assertEquals("Song Title — Some Singer — An Album", parsed[0].metadata)
        assertEquals("paused", parsed[1].state)
        assertNull(parsed[1].metadata)
    }

    @Test
    fun statusText() {
        val text =
            DeviceAudio.status(
                mapOf(
                    "volumes" to volumes,
                    "ringer" to "1",
                    "output" to "BUILTIN_SPEAKER",
                    "sessions" to sessions,
                ),
            )
        assertTrue(text, text.contains("media: 45/150 (30%)"))
        assertTrue(text, text.contains("ring: 0/15 (0%) muted"))
        assertTrue(text, text.contains("ringer_mode: vibrate"))
        assertTrue(text, text.contains("output: BUILTIN_SPEAKER"))
        assertTrue(text, text.contains("com.example.music: playing — Song Title"))
        assertTrue(
            DeviceAudio.status(mapOf("sessions" to "Sessions Stack - have 0 sessions:"))
                .contains("no media session"),
        )
    }

    /** 小米 17 上的真实输出（歌名换掉）：电话的全局会话没有状态，小米音乐停着。 */
    private val hyperOsSessions =
        """
        Global priority session is com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
          HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton/1 (userId=0)
            ownerPid=5199, ownerUid=1000, userId=0
            package=com.android.server.telecom
            active=false
            state=null
            metadata: null
        User Records:
          Last MediaButtonReceiver: MBR {pi=PendingIntent{5d105aa: PendingIntentRecord{100c9b0 tv.danmaku.bili broadcastIntent}}}
          Media button session is null
          Sessions Stack - have 1 sessions:
            QQMusicCar com.miui.player/QQMusicCar/269 (userId=0)
              package=com.miui.player
              active=true
              state=PlaybackState {state=STOPPED(1), position=151020, buffered position=0, speed=1.0, updated=839005131, actions=2360311}
              metadata: size=13, description=Song, Singer, null
        Audio playback (lastly played comes first)
          uid=10463 packages=tv.danmaku.bili
        """
            .trimIndent()

    @Test
    fun parsesHyperOsSessions() {
        val text = DeviceAudio.status(mapOf("sessions" to hyperOsSessions))
        assertTrue(text, text.contains("com.miui.player: stopped — Song — Singer"))
        assertFalse(text, text.contains("telecom"))
    }

    @Test
    fun metadataAndState() {
        assertNull(DeviceAudio.metadataText("null"))
        assertEquals("Title", DeviceAudio.metadataText("size=2, description=Title, null, null"))
        assertNull(DeviceAudio.playbackState("null"))
        assertEquals("buffering", DeviceAudio.playbackState("PlaybackState {state=6, position=0}"))
    }
}
