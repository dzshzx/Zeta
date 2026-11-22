package com.dzshzx.zeta.core

import android.content.Intent
import android.net.Uri

/** 从启动 Zeta 的 Intent 里取出分享或打开的内容；不是这两种、或者什么都没带时返回 null。 */
fun Intake.Companion.fromIntent(intent: Intent?): Intake? {
    intent ?: return null
    val streams =
        when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java),
                )
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.map(Uri::toString)
    val clip = intent.clipData
    val clipItems = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it) }
    return IntakeParser.parse(
        action = intent.action,
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
        streams = streams,
        data = intent.data?.toString(),
        clipUris = clipItems.mapNotNull { it.uri?.toString() },
        clipTexts = clipItems.mapNotNull { it.text?.toString() },
    )
}
