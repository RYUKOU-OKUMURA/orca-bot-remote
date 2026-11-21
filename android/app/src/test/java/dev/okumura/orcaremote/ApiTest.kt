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
    fun botListIncludesMascotAndLatestChat() {
        val result = apiJson.decodeFromString<Bots>(
            """{"bots":[{"id":"bot-1","name":"メインBot","paused":false,"needsYou":1,
                "avatar":"mascot:A1","lastActivityAt":1795230000000,"lastSaid":"回答しました"}]}"""
        )

        assertEquals(Bot("bot-1", "メインBot", null, false, 1, "mascot:A1", 1795230000000, "回答しました"), result.bots.single())
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
    fun omittedSummaryIsNull() {
        assertNull(apiJson.decodeFromString<LatestSummary>("{}").summary)
    }

    @Test
    fun unknownResponseKeysAreIgnored() {
        val result = apiJson.decodeFromString<LatestSummary>(
            """{"newField":true,"summary":{"runAt":1795230000000,"content":"# 午前の状況","bot":"メインBot","newField":42}}"""
        )

        assertEquals(Summary(1795230000000, "# 午前の状況", "メインBot"), result.summary)
    }

    @Test
    fun connectionAcceptsBaseUrlAndHeaderSafeToken() {
        assertNotNull(Connection.parse(" http://100.64.0.1:8787 ", " mock-orca-token "))
        assertNull(Connection.parse("mac-mini:8787", "mock-orca-token"))
        assertNull(Connection.parse("https://example.com/api", "mock-orca-token"))
        assertNull(Connection.parse("https://name:pass@example.com", "mock-orca-token"))
        assertNull(Connection.parse("https://example.com", "mock\nor ca"))
        assertNull(Connection.parse("https://example.com", " "))
    }

    @Test
    fun cleartextIsLimitedToTailscaleHosts() {
        for (host in listOf("100.64.0.0", "100.127.255.255", "[FD7A:115C:A1E0::1]", "bot.ts.net", "Mac-Mini.team.ts.net")) {
            assertNotNull(host, Connection.parse("http://$host:8787", "token", allowLocalhost = false))
        }
        for (host in listOf(
            "100.63.255.255", "100.128.0.0", "100.64.00.1", "100.64.256.1", "100.64.1",
            "[fd7a:115c:a1e1::1]", "[fd7a:115c:a1e0::1%25eth0]", "example.com", "mac-mini",
            "ts.net", "bot.ts.net.", "bot.ts.net.example.com", "bot..ts.net", "-bot.ts.net",
        )) {
            assertNull(host, Connection.parse("http://$host:8787", "token", allowLocalhost = true))
        }
        assertNotNull(Connection.parse("https://example.com:8787", "token", allowLocalhost = false))
    }

    @Test
    fun localHttpRequiresDebugBuildOrExplicitOverride() {
        for (host in listOf("localhost", "10.0.2.2", "127.0.0.1")) {
            val url = "http://$host:8787"
            assertNotNull(host, Connection.parse(url, "token", allowLocalhost = true))
            assertNull(host, Connection.parse(url, "token", allowLocalhost = false))
            assertEquals(host, BuildConfig.DEBUG, Connection.parse(url, "token") != null)
        }
    }

    @Test
    fun workFieldsSupportOldAndNewServers() {
        val oldBot = """{"id":"1","name":"B","paused":false,"needsYou":0}"""
        val bot = apiJson.decodeFromString<Bot>(oldBot)
        assertFalse(bot.working)
        assertNull(bot.activeWork)
        val busy = apiJson.decodeFromString<Bot>(oldBot.dropLast(1) + ""","working":true,"activeWork":"review"}""")
        assertTrue(busy.working)
        assertEquals("review", busy.activeWork)
        assertFalse(apiJson.decodeFromString<Chat>("""{"id":"1","home":true}""").working)
        assertTrue(apiJson.decodeFromString<Chat>("""{"id":"1","home":true,"working":true}""").working)
        assertTrue(apiJson.decodeFromString<Messages>("""{"entries":[]}""").activeWorkers.isEmpty())
        val workers = apiJson.decodeFromString<Messages>("""{"entries":[],"activeWorkers":[{"title":"review"}]}""").activeWorkers
        assertEquals(listOf(ActiveWorker("review")), workers)
        assertTrue(shouldPoll(null, null, workers))
        assertFalse(shouldPoll(null, ApiError.UNAUTHORIZED, workers))
        assertTrue(shouldPoll("running", null))
    }

    @Test
    fun onlyWorkingStatePolls() {
        assertTrue(shouldPoll("working", null))
        for (state in listOf(null, "done", "idle", "blocked", "waiting", "unknown")) {
            assertFalse("Unexpected polling state: $state", shouldPoll(state, null))
        }
    }

    @Test
    fun pollingContinuesOnlyAfterTransientErrorsWhileWorking() {
        assertFalse(shouldPoll("working", ApiError.UNAUTHORIZED))
        assertFalse(shouldPoll("working", ApiError.NOT_FOUND))
        assertFalse(shouldPoll("working", ApiError.RESPONSE))
        assertTrue(shouldPoll("working", ApiError.ORCA))
        assertTrue(shouldPoll("working", ApiError.TIMEOUT))
        assertTrue(shouldPoll("working", ApiError.CONNECTION))
        assertFalse(shouldPoll("done", ApiError.ORCA))
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
        assertEquals("5m", relativeTime(now - 5 * minute, now, compact = true))
        assertEquals("6h", relativeTime(now - 360 * minute, now, compact = true))
        assertEquals("1d", relativeTime(now - 1_440 * minute, now, compact = true))
    }
}
