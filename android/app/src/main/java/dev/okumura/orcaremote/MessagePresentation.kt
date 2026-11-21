package dev.okumura.orcaremote

internal enum class MessageKind { OWNER, MAIL, OTHER }

internal data class MessagePresentation(
    val kind: MessageKind,
    val heading: String,
    val body: String,
    val subject: String? = null,
)

private val mailHeader = Regex("──── From: .+ \\(([\\w-]+)\\) ────")
private val mailRule = Regex("─+")
private const val replyPrefix = "[Reply: orca orchestration reply"
// Treat CRLF as a single line break when checking for the blank line.
private val ownerMetadata = Regex("^orcaTabId: structured-agent-session-[^\\r\\n]+(?>\\r\\n|\\r|\\n){2}")

internal fun presentMessage(role: String, text: String): MessagePresentation {
    if (role != "user") {
        return MessagePresentation(MessageKind.OTHER, if (role == "assistant") "Bot" else role, text)
    }
    val lines = text.lines()
    val type = mailHeader.matchEntire(lines.first())?.groupValues?.get(1)
    if (type == null) {
        return MessagePresentation(MessageKind.OWNER, "あなた", ownerMetadata.replaceFirst(text, ""))
    }

    val subjectLine = lines.getOrNull(1)?.takeIf { it.startsWith("Subject:") }
    val bodyLines = lines.drop(if (subjectLine == null) 1 else 2)
    val lastLineIndex = bodyLines.indexOfLast { it.isNotBlank() }
    val replyIndex = if (bodyLines.getOrNull(lastLineIndex)?.matches(mailRule) == true) {
        lastLineIndex - 1
    } else {
        lastLineIndex
    }
    val body = if (bodyLines.getOrNull(replyIndex)?.startsWith(replyPrefix) == true) {
        bodyLines.take(replyIndex)
    } else {
        bodyLines
    }
    return MessagePresentation(
        kind = MessageKind.MAIL,
        heading = "メール（$type）",
        body = body.joinToString("\n"),
        subject = subjectLine?.removePrefix("Subject:")?.trim()?.takeIf { it.isNotEmpty() },
    )
}
