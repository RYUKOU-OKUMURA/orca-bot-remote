package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class QuickMemoTest {
    @Test fun linksAndVoiceText() {
        assertEquals(false, memoVoice("orcaremote://quick-memo"))
        assertEquals(true, memoVoice("orcaremote://quick-memo?voice=1"))
        assertEquals(false, memoVoice("orcaremote://quick-memo?voice=0"))
        assertNull(memoVoice("orcaremote://items"))
        assertNull(memoVoice("https://quick-memo?voice=1"))
        assertEquals("本文\n追記", appendSpeech("本文", " 追記 "))
        assertEquals("追記", appendSpeech("", "追記"))
        assertEquals("元 ", appendSpeech("元 ", null))
        assertEquals("元 追記", appendSpeech("元 ", "追記"))
    }

    @Test fun preferredDestination() {
        val one = Bot("1","他", paused = false, needsYou = 0)
        val main = one.copy(id = "2", name = "メインBot")
        assertEquals(one, memoBot(listOf(one,main),"他"))
        assertEquals(main, memoBot(listOf(one,main),"削除済み"))
        assertEquals(one, memoBot(listOf(one),null))
        assertNull(memoBot(emptyList(),null))
    }
}
