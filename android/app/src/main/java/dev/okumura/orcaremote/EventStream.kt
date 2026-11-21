package dev.okumura.orcaremote

import java.io.BufferedReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal data class StreamEvent(val event: String, val data: String)

// BufferedReader joins packet fragments and recognizes LF, CRLF and CR.
internal suspend fun readEvents(reader: BufferedReader, receive: suspend (StreamEvent) -> Unit) {
    var event = ""
    val data = mutableListOf<String>()
    var first = true
    while (true) {
        val raw = reader.readLine() ?: return
        val line = if (first) raw.removePrefix("\uFEFF") else raw
        first = false
        if (line.isEmpty()) {
            if (data.isNotEmpty()) receive(StreamEvent(event.ifEmpty { "message" }, data.joinToString("\n")))
            event = ""
            data.clear()
        } else {
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (line.substringBefore(':')) {
                "event" -> event = value
                "data" -> data.add(value)
            }
        }
    }
}

@Serializable private data class ReadyEvent(val at: Long)
@Serializable private data class ChangeEvent(val items: Boolean, val summary: Boolean, val bots: Boolean, val at: Long)

internal fun StreamEvent.triggersSync(): Boolean = try {
    when (event) {
        "ready" -> { apiJson.decodeFromString<ReadyEvent>(data); true }
        "change" -> { apiJson.decodeFromString<ChangeEvent>(data); true }
        else -> false
    }
} catch (_: SerializationException) { false }

internal fun reconnectDelay(failures: Int) = when (failures) {
    0 -> 3_000L
    1 -> 6_000L
    2 -> 12_000L
    3 -> 30_000L
    else -> 60_000L
}
