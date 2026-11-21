package dev.okumura.orcaremote

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTitleTest {
    @Test fun scheduledTimesUseDeviceZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        for ((utc, local) in listOf("23" to "08", "03" to "12", "09" to "18")) {
            val at = Instant.parse("2026-11-21T${utc}:00:00Z").toEpochMilli()
            assertEquals("${local}:00のまとめ", summaryTitle(at, tokyo))
        }
        assertEquals("秘書まとめ", summaryTitle(null, tokyo))
    }
}
