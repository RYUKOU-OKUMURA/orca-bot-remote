package dev.okumura.orcaremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {
    @Test
    fun botList() {
        val result = apiJson.decodeFromString<Bots>(
            """{"bots":[{"id":"bot-1","name":"メインBot","specialty":null,"paused":false,"needsYou":1}]}"""
        )

        assertEquals(Bot("bot-1", "メインBot", null, false, 1), result.bots.single())
    }

    @Test
    fun chatList() {
        val result = apiJson.decodeFromString<Chats>(
            """{"chats":[{"id":"chat-1","title":null,"home":true,"lastActivityAt":1795230000000,
                "lastAsked":"確認してください","lastSaid":null}]}"""
        )

        assertEquals(Chat("chat-1", null, true, 1795230000000, "確認してください", null), result.chats.single())
    }

    @Test
    fun messagesAreDecodedInApiOrder() {
        val result = apiJson.decodeFromString<Messages>(
            """{"state":"working","entries":[
                {"kind":"message","role":"user","text":"こんにちは"},
                {"kind":"tool","name":"Read","state":null},
                {"kind":"message","role":"assistant","text":"確認しました"},
                {"kind":"message","role":"system","text":"記録"}
            ]}"""
        )

        assertEquals("working", result.state)
        assertEquals(
            listOf(
                Entry.Message("user", "こんにちは"),
                Entry.Tool("Read"),
                Entry.Message("assistant", "確認しました"),
                Entry.Message("system", "記録"),
            ),
            result.entries,
        )
    }

    @Test
    fun pendingItemsAndQuestions() {
        val result = apiJson.decodeFromString<Items>(
            """{"items":[{"bot":"メインBot","number":7,"title":"公開時間","status":"needs_you",
                "chatId":"chat-1","lastActivityAt":null}]}"""
        )
        val item = apiJson.decodeFromString<ItemDetail>(
            """{"bot":"メインBot","number":7,"title":"公開時間","status":"needs_you","chatId":"chat-1",
                "question":{"text":"いつ公開しますか？","options":["今日","明日"]}}"""
        )

        assertEquals(PendingItem("メインBot", 7, "公開時間", "needs_you", "chat-1"), result.items.single())
        assertEquals(
            ItemDetail("メインBot", 7, "公開時間", "needs_you", "chat-1", Question("いつ公開しますか？", listOf("今日", "明日"))),
            item,
        )
    }

    @Test
    fun missingOptionalData() {
        val item = apiJson.decodeFromString<ItemDetail>(
            """{"bot":"メインBot","number":7,"title":"公開時間","status":"needs_you","chatId":null,"question":null}"""
        )
        assertNull(item.chatId)
        assertNull(item.question)
        assertNull(apiJson.decodeFromString<LatestSummary>("""{"summary":null}""").summary)
        assertNull(apiJson.decodeFromString<Messages>("""{"state":null,"entries":[]}""").state)
    }

    @Test
    fun summaryIncludesBotForChatLink() {
        val result = apiJson.decodeFromString<LatestSummary>(
            """{"summary":{"runAt":1795230000000,"content":"# 午前の状況","bot":"メインBot","chatId":"chat-1"}}"""
        )

        assertEquals(Summary(1795230000000, "# 午前の状況", "メインBot", "chat-1"), result.summary)
    }

    @Test
    fun connectionAcceptsBaseUrlAndHeaderSafeToken() {
        assertNotNull(Connection.parse(" http://mac-mini:8787 ", " mock-orca-token "))
        assertNotNull(Connection.parse("http://[fd7a:115c:a1e0::1234]:8787", "mock-orca-token"))
        assertNull(Connection.parse("mac-mini:8787", "mock-orca-token"))
        assertNull(Connection.parse("http://mac-mini:8787/api", "mock-orca-token"))
        assertNull(Connection.parse("http://name:pass@mac-mini:8787", "mock-orca-token"))
        assertNull(Connection.parse("http://mac-mini:8787", "mock\nor ca"))
        assertNull(Connection.parse("http://mac-mini:8787", " "))
    }

    @Test
    fun onlyWorkingStatePolls() {
        assertTrue(shouldPoll("working"))
        for (state in listOf(null, "done", "idle", "blocked", "waiting", "unknown")) {
            assertFalse("Unexpected polling state: $state", shouldPoll(state))
        }
    }

    @Test
    fun relativeTimeHandlesUnitsFutureAndMissingTime() {
        val minute = 60_000L
        val now = 10_000 * minute

        assertNull(relativeTime(null, now))
        assertEquals("たった今", relativeTime(now + minute, now))
        assertEquals("たった今", relativeTime(now - minute + 1, now))
        assertEquals("5分前", relativeTime(now - 5 * minute, now))
        assertEquals("1時間前", relativeTime(now - 60 * minute, now))
        assertEquals("1日前", relativeTime(now - 1_440 * minute, now))
    }
}
