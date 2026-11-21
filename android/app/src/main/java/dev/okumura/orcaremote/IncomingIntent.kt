package dev.okumura.orcaremote

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.os.Build
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
internal data class SharedDraft(val text: String, val uris: List<String>)

@Serializable
internal data class ExternalOpen(val link: String? = null, val share: SharedDraft? = null, val coldStart: Boolean)

internal fun sharedDraft(action: String?, subject: String?, text: String?, streams: List<String>, clips: List<String>): SharedDraft? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val title = subject?.trim().orEmpty()
    val body = text.orEmpty()
    val combined = when {
        body.isBlank() -> title
        title.isEmpty() || body.trimStart().startsWith(title) -> body
        else -> "$title\n$body"
    }
    return SharedDraft(combined, (streams + clips).distinct()).takeIf { it.text.isNotBlank() || it.uris.isNotEmpty() }
}

internal fun memoVoice(link: String): Boolean? {
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    if (uri.scheme != "orcaremote" || uri.rawAuthority != "quick-memo" || !uri.path.isNullOrEmpty()) return null
    return uri.rawQuery.orEmpty().split('&').contains("voice=1")
}

@Suppress("DEPRECATION")
internal fun Intent.externalOpen(coldStart: Boolean): ExternalOpen? {
    if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
        return try {
            val streams = if (action == Intent.ACTION_SEND_MULTIPLE) {
                if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            } else listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
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
    return data?.takeIf { it.scheme == NotificationScheme || (it.scheme == "orcaremote" && (memoVoice(it.toString()) != null || it.authority == "items")) }?.let { ExternalOpen(link = it.toString(), coldStart = coldStart) }
}
