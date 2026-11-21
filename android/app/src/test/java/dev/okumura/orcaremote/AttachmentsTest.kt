package dev.okumura.orcaremote

import androidx.lifecycle.SavedStateHandle
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AttachmentsTest {
    @Test fun conversationLimitsIncludeLastFileAndByte() {
        val limit = AttachmentLimits.Conversation
        assertNull(limit.error(9, 180 * MiB, 20 * MiB))
        assertNull(limit.error(0, 0, 1))
        assertNotNull(limit.error(0, 0, 0))
        assertNotNull(limit.error(10, 10, 1))
        assertNotNull(limit.error(0, 0, 20 * MiB + 1))
    }

    @Test fun newChatLimitsCheckCountIndividualAndCombinedBytes() {
        val limit = AttachmentLimits.NewChat
        assertNull(limit.error(0, 0, 15 * MiB))
        assertNull(limit.error(4, 5 * MiB, 15 * MiB))
        assertNotNull(limit.error(4, 5 * MiB + 1, 15 * MiB))
        assertNotNull(limit.error(1, 1, 15 * MiB + 1))
        assertNotNull(limit.error(5, 5, 1))
        assertNotNull(limit.error(0, 0, 0))
    }

    private fun draft(vararg names: String, limits: AttachmentLimits): ChatDraft {
        val files = names.map { LocalAttachment(it, it, "text/plain", 1) }
        return ChatDraft(SavedStateHandle(mapOf("attachments" to apiJson.encodeToString(files))), limits)
    }

    @Test fun partialFailureReusesSuccessfulUploadsOnRetry() = runBlocking {
        val chat = draft("first", "second", limits = AttachmentLimits.Conversation)
        val attempts = mutableListOf<String>()
        suspend fun upload(file: LocalAttachment): UploadedAttachment {
            attempts += file.name
            if (attempts.size == 2) throw ApiFailure(ApiError.CONNECTION)
            return UploadedAttachment("/" + file.name, file.name, "file")
        }
        try {
            chat.uploadPending(::upload)
            fail("second upload should fail")
        } catch (e: ApiFailure) {
            assertEquals(ApiError.CONNECTION, e.error)
        }
        assertEquals(listOf("/first", "/second"), chat.uploadPending(::upload).map { it.path })
        assertEquals(listOf("first", "second", "second"), attempts)
    }

    @Test fun inlineReadsActualBytesAndEnforcesBothCaps() = runBlocking {
        val draft = draft("first", "second", limits = AttachmentLimits.NewChat)
        assertEquals(listOf("YQ==", "YQ=="), draft.inline { "a".byteInputStream() }.map { it.contentBase64 })
        for ((first, second) in listOf(0 to 1, (15 * MiB + 1).toInt() to 1, (15 * MiB).toInt() to (5 * MiB + 1).toInt())) {
            try {
                draft.inline { ByteArrayInputStream(ByteArray(if (it.name == "first") first else second)) }
                fail("invalid actual size should fail")
            } catch (e: AttachmentProblem) {
                assertNotNull(e.message)
            }
        }
    }

    @Test fun sizesUseBinaryUnits() {
        assertEquals("1 B", fileSize(1))
        assertEquals("1023 B", fileSize(1023))
        assertEquals("1.5 KiB", fileSize(1536))
        assertEquals("1.2 MiB", fileSize(1_258_291))
    }
}
