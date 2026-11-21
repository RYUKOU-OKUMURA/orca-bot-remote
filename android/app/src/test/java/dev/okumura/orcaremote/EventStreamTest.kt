package dev.okumura.orcaremote

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EventStreamTest {
    @Test fun fragmentedUtf8CommentsAndMultilineData() = runTest {
        val text = "\uFEFF: ping\r\nevent: ready\rdata: {\"at\":1}\r\r" +
            "event: change\ndata: {\"items\":true,\ndata: \"summary\":false,\"bots\":true,\"at\":2,\"extra\":\"返信\"}\n\n" +
            "event: ready\ndata: {\"at\":3}"
        val stream = object : FilterInputStream(ByteArrayInputStream(text.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        val events = mutableListOf<StreamEvent>()
        readEvents(stream.bufferedReader(), events::add)
        assertEquals(listOf("ready", "change"), events.map { it.event })
        assertTrue(events[1].data.contains('\n'))
        assertEquals(listOf(SyncTarget.BOTH, SyncTarget.BOTH), events.map { it.syncTarget() })
    }

    @Test fun malformedAndUnknownEventsDoNotInterruptNextFrame() = runTest {
        val sync = mutableListOf<SyncTarget?>()
        readEvents("event: change\ndata: not-json\n\nevent: change\ndata: {}\n\ndata: {}\n\nevent: ready\ndata: {\"at\":5}\n\n".reader().buffered()) {
            sync += it.syncTarget()
        }
        assertEquals(listOf(null, null, null, SyncTarget.BOTH), sync)
    }

    @Test fun changeFlagsSelectOnlyAffectedSnapshots() {
        fun change(items: Boolean = false, summary: Boolean = false, bots: Boolean = false, board: Boolean? = null): SyncTarget? {
            val optionalBoard = board?.let { ""","board":$it""" }.orEmpty()
            val data = """{"at":2,"items":$items,"summary":$summary,"bots":$bots$optionalBoard}"""
            return StreamEvent("change", data).syncTarget()
        }
        assertEquals(SyncTarget.BOARD, change(board = true))
        assertEquals(SyncTarget.NOTIFICATIONS, change(items = true, board = false))
        assertEquals(SyncTarget.NOTIFICATIONS, change(summary = true))
        assertEquals(SyncTarget.BOTH, change(bots = true))
        assertEquals(SyncTarget.BOTH, change(items = true, board = true))
        assertEquals(SyncTarget.BOTH, change(summary = true, board = true))
        assertNull(change())
    }

    @Test fun reconnectBackoffIsCapped() {
        assertEquals(listOf(3000L, 6000L, 12000L, 30000L, 60000L, 60000L), (0..5).map(::reconnectDelay))
    }
}
