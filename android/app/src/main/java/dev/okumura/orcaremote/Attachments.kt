package dev.okumura.orcaremote

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer

internal const val MiB = 1024L * 1024
internal enum class AttachmentLimits(val count: Int, val perFile: Long, val total: Long) {
    Conversation(10, 20 * MiB, 200 * MiB),
    NewChat(5, 15 * MiB, 20 * MiB);

    fun error(existingCount: Int, existingBytes: Long, size: Long): String? = when {
        existingCount >= count -> "添付できるのは最大 ${count} 件です"
        size <= 0 -> "空のファイルは添付できません"
        size > perFile -> "1件あたり ${perFile / MiB} MiB まで添付できます"
        size > total - existingBytes -> "合計 ${total / MiB} MiB まで添付できます"
        else -> null
    }
}

internal fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < MiB -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / MiB.toDouble())
}

@Serializable
internal data class LocalAttachment(
    val uri: String, val name: String, val mimeType: String?, val size: Long,
    val uploaded: AttachmentRef? = null,
) {
    fun sanitized(): LocalAttachment {
        val safeName = name.filterNot(Char::isISOControl).trim().ifEmpty { "添付ファイル" }
        val safeMime = mimeType?.takeIf { it.length <= 256 && it.isNotBlank() && it.none(Char::isISOControl) }
        if (safeName.length <= 256) return copy(name = safeName, mimeType = safeMime)

        val dot = safeName.lastIndexOf('.')
        val suffix = if (dot > 0 && safeName.length - dot in 2 until 256) safeName.substring(dot) else ""
        val cut = 256 - suffix.length
        val end = if (safeName[cut - 1].isHighSurrogate() && safeName[cut].isLowSurrogate()) cut - 1 else cut
        return copy(name = safeName.take(end) + suffix, mimeType = safeMime)
    }

    fun open(resolver: ContentResolver) = resolver.open(Uri.parse(uri))
    val uploadType get() = mimeType?.toMediaTypeOrNull()?.takeUnless { it.type == "multipart" }?.toString() ?: "application/octet-stream"
    val image get() = mimeType?.startsWith("image/", ignoreCase = true) == true ||
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif")
}

internal class AttachmentProblem(message: String) : Exception(message)

private fun ContentResolver.open(uri: Uri): InputStream = try {
    openInputStream(uri) ?: throw IOException("ファイルを開けません")
} catch (e: SecurityException) {
    throw IOException("ファイルの読み取り権限がありません", e)
}

private suspend fun InputStream.copyLimited(limit: Long, chunk: (ByteArray, Int) -> Unit): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = read(buffer)
        if (read == -1) break
        size += read
        if (size > limit) break
        chunk(buffer, read)
    }
    return size
}

internal class ChatDraft(private val saved: SavedStateHandle, limits: AttachmentLimits) {
    var limits by mutableStateOf(limits)
        private set
    var attachments by mutableStateOf(saved.get<String>("attachments")?.let {
        apiJson.decodeFromString<List<LocalAttachment>>(it).map(LocalAttachment::sanitized)
    }.orEmpty())
        private set
    var skills by mutableStateOf(saved.get<String>("skills")?.let { apiJson.decodeFromString<List<SkillRef>>(it) }.orEmpty())
        private set
    var notice by mutableStateOf<String?>(null)
    var reading by mutableStateOf(false)
        private set

    private fun update(files: List<LocalAttachment>) {
        attachments = files
        saved["attachments"] = apiJson.encodeToString(files)
    }

    private fun updateSkills(values: List<SkillRef>) {
        skills = values
        saved["skills"] = apiJson.encodeToString(values)
    }

    fun changeDestination(newLimits: AttachmentLimits): Boolean {
        var bytes = 0L
        for ((index, file) in attachments.withIndex()) {
            newLimits.error(index, bytes, file.size)?.let {
                notice = "送信先を変更するには添付を減らしてください。$it"
                return false
            }
            bytes += file.size
        }
        limits = newLimits
        update(attachments.map { it.copy(uploaded = null) })
        notice = null
        return true
    }

    fun addSkill(skill: SkillRef) {
        if (skill in skills) return
        if (skills.size == 16) notice = "スキルは最大16件です" else updateSkills(skills + skill)
    }

    fun removeSkill(skill: SkillRef) = updateSkills(skills - skill)
    fun remove(file: LocalAttachment) = update(attachments - file)
    fun clear() {
        update(emptyList())
        updateSkills(emptyList())
        notice = null
    }

    suspend fun add(resolver: ContentResolver, uris: List<Uri>) {
        if (reading || uris.isEmpty()) return
        reading = true
        notice = null
        try {
            for (uri in uris) {
                if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                    notice = "このファイルを開けません。ファイル選択画面から選び直してください"
                    continue
                }
                if (attachments.any { it.uri == uri.toString() }) continue
                if (attachments.size >= limits.count) {
                    notice = "添付できるのは最大 ${limits.count} 件です"
                    break
                }
                val total = attachments.sumOf { it.size }
                try {
                    val file = withContext(Dispatchers.IO) {
                        var name: String? = null
                        var size: Long? = null
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { rows ->
                            if (rows.moveToFirst()) {
                                rows.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = rows.getString(it) }
                                rows.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !rows.isNull(it) }
                                    ?.let { size = rows.getLong(it).takeIf { bytes -> bytes >= 0 } }
                            }
                        }
                        val bytes = resolver.open(uri).use { input ->
                            size ?: input.copyLimited(minOf(limits.perFile, limits.total - total)) { _, _ -> }
                        }
                        val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "添付ファイル"
                        val item = LocalAttachment(uri.toString(), displayName, resolver.getType(uri), bytes).sanitized()
                        limits.error(attachments.size, total, bytes)?.let { throw AttachmentProblem("${item.name}：$it") }
                        item
                    }
                    update(attachments + file)
                } catch (e: AttachmentProblem) {
                    notice = e.message
                } catch (_: IOException) {
                    notice = "ファイルを読み取れません。もう一度選んでください"
                } catch (_: RuntimeException) {
                    currentCoroutineContext().ensureActive()
                    notice = "ファイル情報を取得できません。もう一度選んでください"
                }
            }
        } finally {
            reading = false
        }
    }

    suspend fun uploadPending(upload: suspend (LocalAttachment) -> UploadedAttachment): List<AttachmentRef> {
        return attachments.map { file ->
            file.uploaded ?: run {
                val receipt = try {
                    upload(file)
                } catch (e: ApiFailure) {
                    if (e.error != ApiError.UNKNOWN_RESULT) throw e
                    throw AttachmentProblem("${file.name} のアップロードを確認できません。メッセージはまだ送信していません")
                }
                val ref = AttachmentRef(receipt.path, receipt.name, receipt.kind)
                update(attachments.map { if (it.uri == file.uri) it.copy(uploaded = ref) else it })
                ref
            }
        }
    }

    suspend fun sendMessage(api: OrcaApi, bot: String, chatId: String, text: String, open: (LocalAttachment) -> InputStream) {
        val refs = uploadPending { file ->
            api.uploadAttachment(bot, chatId, file.name, file.uploadType, file.size) { open(file) }
        }
        try {
            api.sendMessage(bot, chatId, text, skills, refs)
        } catch (failure: ApiFailure) {
            if (failure.error == ApiError.BAD_REQUEST && refs.isNotEmpty()) {
                update(attachments.map { it.copy(uploaded = null) })
            }
            throw failure
        }
    }

    suspend fun inline(open: (LocalAttachment) -> InputStream): List<InlineAttachment> = withContext(Dispatchers.IO) {
        var total = 0L
        attachments.mapIndexed { index, file ->
            val buffer = Buffer()
            val size = try {
                open(file).use { input ->
                    Base64.getEncoder().wrap(buffer.outputStream()).use { encoder ->
                        input.copyLimited(minOf(limits.perFile, limits.total - total)) { bytes, count -> encoder.write(bytes, 0, count) }
                    }
                }
            } catch (_: IOException) {
                throw AttachmentProblem("${file.name} を読み取れません。もう一度選んでください")
            }
            limits.error(index, total, size)?.let { throw AttachmentProblem("${file.name}：$it") }
            total += size
            InlineAttachment(file.name, file.mimeType, buffer.readUtf8())
        }
    }
}

internal suspend fun thumbnail(resolver: ContentResolver, file: LocalAttachment) = withContext(Dispatchers.IO) {
    try {
        val uri = Uri.parse(file.uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
        resolver.open(uri).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        val longest = maxOf(options.outWidth, options.outHeight)
        while (longest / options.inSampleSize > 256) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        resolver.open(uri).use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
