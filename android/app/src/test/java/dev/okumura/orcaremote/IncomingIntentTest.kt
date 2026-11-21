package dev.okumura.orcaremote

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class IncomingIntentTest {
    @Test fun textAndStreamsFromBothSources() {
        val single = sharedDraft(Intent.ACTION_SEND, "件名", "本文", listOf("content://a"), listOf("content://a"))
        assertEquals(SharedDraft("件名\n本文", listOf("content://a")), single)
        assertEquals("A\nAndroid", sharedDraft(Intent.ACTION_SEND, "A", "Android", emptyList(), emptyList())?.text)
        val multi = sharedDraft(Intent.ACTION_SEND_MULTIPLE, "件名", "件名\n本文",
            listOf("content://a", "content://b"), listOf("content://b", "content://c"))
        assertEquals(SharedDraft("件名\n本文", listOf("content://a", "content://b", "content://c")), multi)
        assertNull(sharedDraft(Intent.ACTION_VIEW, "件名", null, emptyList(), emptyList()))
        assertNull(sharedDraft(Intent.ACTION_SEND, null, "", emptyList(), emptyList()))
    }
}
