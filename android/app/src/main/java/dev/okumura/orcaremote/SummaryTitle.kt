package dev.okumura.orcaremote

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

internal fun localClock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    clockFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

internal fun summaryTitle(runAt: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    runAt?.let { "${localClock(it, zone)}のまとめ" } ?: "秘書まとめ"
