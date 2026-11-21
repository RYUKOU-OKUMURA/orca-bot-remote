package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class QuickMemoTest {
    @Test fun secretaryLinksAndEnvelope() {
        assertEquals(QuickMemoRoute(inbox = true), quickMemoLink("orcaremote://quick-memo?inbox=1"))
        assertEquals(QuickMemoRoute(inbox = true, voice = true), quickMemoLink("orcaremote://quick-memo?voice=1&inbox=1"))
        assertEquals(QuickMemoRoute(), quickMemoLink("orcaremote://quick-memo?inbox=10"))
        assertEquals("【秘書メモ・自動整理】\n買い物", MemoMode.Inbox.message("買い物"))
        for (mode in listOf(MemoMode.Home, MemoMode.New)) assertEquals("本文", mode.message("本文"))
    }

    @Test fun linksAndVoiceText() {
        fun memoVoice(link: String) = quickMemoLink(link)?.voice
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
