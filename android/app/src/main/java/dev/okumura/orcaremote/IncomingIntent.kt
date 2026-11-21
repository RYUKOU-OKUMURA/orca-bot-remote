package dev.okumura.orcaremote

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.core.content.IntentCompat
import kotlinx.serialization.Serializable
import java.net.URI

// The server accepts up to 10,000 Unicode code points per message.
internal const val MaxSharedText = 10_000

@Serializable
internal data class SharedDraft(val text: String, val uris: List<String>, val textTruncated: Boolean = false)

@Serializable
internal data class ExternalOpen(val link: String? = null, val share: SharedDraft? = null, val coldStart: Boolean)

internal fun sharedDraft(action: String?, subject: String?, text: String?, streams: List<String>, clips: List<String>): SharedDraft? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val title = subject?.trim().orEmpty()
    val body = text.orEmpty()
    val trimmed = body.trimStart()
    val includesTitle = trimmed.startsWith(title) &&
        (trimmed.length == title.length || trimmed.getOrNull(title.length)?.isWhitespace() == true)
    val combined = when {
        body.isBlank() -> title
        title.isEmpty() || includesTitle -> body
        else -> "$title\n$body"
    }
    val truncated = combined.codePointCount(0, combined.length) > MaxSharedText
    val bounded = if (truncated) combined.substring(0, combined.offsetByCodePoints(0, MaxSharedText)) else combined
    val uris = (streams + clips).distinct()
    if (combined.isBlank() && uris.isEmpty()) return null
    return SharedDraft(bounded, uris, truncated)
}

@Serializable
internal data class QuickMemoRoute(val voice: Boolean = false, val inbox: Boolean = false)

internal fun quickMemoLink(link: String): QuickMemoRoute? {
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    if (uri.scheme != "orcaremote" || uri.rawAuthority != "quick-memo" || !uri.path.isNullOrEmpty()) return null
    val flags = uri.rawQuery.orEmpty().split('&')
    return QuickMemoRoute(voice = "voice=1" in flags, inbox = "inbox=1" in flags)
}

internal fun Intent.externalOpen(coldStart: Boolean): ExternalOpen? {
    if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
        return try {
            val streams = if (action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            } else listOfNotNull(
                IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            )
            val clips = clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() }
            }.orEmpty()
            sharedDraft(action, getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(), getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                streams.orEmpty().filterIsInstance<Uri>().map(Uri::toString), clips)?.let { ExternalOpen(share = it, coldStart = coldStart) }
        } catch (_: BadParcelableException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }
    return data?.takeIf { it.scheme == NotificationScheme || (it.scheme == "orcaremote" && (quickMemoLink(it.toString()) != null || it.authority == "items")) }?.let { ExternalOpen(link = it.toString(), coldStart = coldStart) }
}
