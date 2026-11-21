package dev.okumura.orcaremote

internal enum class MessageKind { OWNER, MAIL, OTHER }

internal data class MessagePresentation(
    val kind: MessageKind,
    val heading: String,
    val body: String,
    val subject: String? = null,
)

private val mailKind = Regex("\\(([\\w-]+)\\)\\s*─*\\s*$")
private val mailRule = Regex("─+")
private val ownerMetadata = Regex("^orcaTabId:[^\\r\\n]*(?:\\r?\\n(?:[ \\t]*\\r?\\n)?)?")

internal fun presentMessage(role: String, text: String): MessagePresentation {
    if (role != "user") {
        return MessagePresentation(MessageKind.OTHER, if (role == "assistant") "Bot" else role, text)
    }
    if (!text.startsWith("──── From:")) {
        return MessagePresentation(MessageKind.OWNER, "あなた", ownerMetadata.replaceFirst(text, ""))
    }

    val lines = text.lines()
    val type = mailKind.find(lines.first())?.groupValues?.get(1)
    val subjectLine = lines.getOrNull(1)?.takeIf { it.startsWith("Subject:") }
    val bodyLines = lines.drop(if (subjectLine == null) 1 else 2)
        .filterNot { it.trim().matches(mailRule) }
        .dropLastWhile { it.isBlank() }
    val lastLine = bodyLines.lastOrNull()?.trim().orEmpty()
    val body = if (lastLine.startsWith("[Reply:") && lastLine.endsWith(']')) {
        bodyLines.dropLast(1).dropLastWhile { it.isBlank() }
    } else {
        bodyLines
    }
    return MessagePresentation(
        kind = MessageKind.MAIL,
        heading = type?.let { "メール（$it）" } ?: "メール",
        body = body.joinToString("\n"),
        subject = subjectLine?.removePrefix("Subject:")?.trim()?.takeIf { it.isNotEmpty() },
    )
}
