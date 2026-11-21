package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class NotificationActionTest {
    @Test fun firstTwoChoicesAndExactlyOneReply() {
        assertEquals(listOf(ResponseAction("はい", false), ResponseAction("後で", false), ResponseAction("返信", true)),
            responseActions(listOf("はい", "後で", "いいえ")))
        assertEquals(listOf(ResponseAction("返信", true)), responseActions(emptyList()))
    }

    @Test fun extractsOnlyTheSelectedSourceAndRejectsBlankReply() {
        assertEquals("本文", responseText(true, "choice", StringBuilder(" 本文 ")))
        assertNull(responseText(true, "choice", "  \n"))
        assertNull(responseText(true, "choice", null))
        assertEquals("選択肢", responseText(false, "選択肢", "different"))
    }
}
