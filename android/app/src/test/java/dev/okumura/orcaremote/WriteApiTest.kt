package dev.okumura.orcaremote

import androidx.lifecycle.SavedStateHandle
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteApiTest {
    @Test
    fun healthChecksOnlyTheAuthenticatedHealthEndpoint() {
        val requests = mutableListOf<List<String>>()
        withServer({ exchange ->
            requests.add(listOf(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization")))
            when (requests.size) {
                1 -> exchange.reply(200, """{"ok":true}""")
                2 -> exchange.reply(401, "{}")
                else -> exchange.reply(200, """{"ok":false}""")
            }
        }) { api ->
            api.health()
            assertEquals(ApiError.UNAUTHORIZED, failureFrom { api.health() })
            assertEquals(ApiError.RESPONSE, failureFrom { api.health() })
        }
        assertEquals(List(3) { listOf("GET", "/api/health", "Bearer mock-token") }, requests)
    }

    @Test
    fun onlyFailuresKnownToPrecedeDeliveryAreConnectionErrors() = runBlocking {
        val connection = requireNotNull(Connection.parse("https://example.com", "token"))
        for ((exception, expected) in listOf(
            ConnectException() to ApiError.CONNECTION,
            UnknownHostException() to ApiError.CONNECTION,
            NoRouteToHostException() to ApiError.CONNECTION,
            SocketTimeoutException() to ApiError.UNKNOWN_RESULT,
            EOFException() to ApiError.UNKNOWN_RESULT,
            IOException() to ApiError.UNKNOWN_RESULT,
        )) {
            val client = OrcaApi.writeClient.newBuilder().addInterceptor { throw exception }.build()
            val api = OrcaApi(connection, client)
            assertEquals(expected, failureFrom { api.sendMessage("bot", "chat", "一回だけ") })
        }
    }

    @Test
    fun writeEndpointsDecodeReceiptsAndSendOwnerText() {
        val routes = mapOf(
            "/api/bots/a%2Fb/chats" to (201 to """{"chatId":"new-id"}"""),
            "/api/bots/a%2Fb/chats/chat-id/messages" to (202 to """{"ok":true}"""),
            "/api/items/a%2Fb/7/answer" to (200 to """{"ok":true}"""),
        )
        val requests = mutableListOf<List<String>>()
        withServer({ exchange ->
            requests.add(listOf(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestBody.bufferedReader(Charsets.UTF_8).readText(),
            ))
            val (status, receipt) = routes.getValue(exchange.requestURI.rawPath)
            exchange.reply(status, receipt)
        }) { api ->
            val label = "公開する\n明日の9:00"
            assertEquals("new-id", api.startChat("a/b", label))
            api.sendMessage("a/b", "chat-id", label)
            api.answer("a/b", 7, label)
        }

        assertEquals(routes.keys.toList(), requests.map { it[1] })
        for ((method, _, bearer, contentType, body) in requests) {
            assertEquals("POST", method)
            assertEquals("Bearer mock-token", bearer)
            assertTrue(contentType.startsWith("application/json"))
            assertEquals("""{"text":"公開する\n明日の9:00"}""", body)
        }
    }

    @Test
    fun errorCodesDistinguishRejectionFromUncertainDelivery() {
        assertEquals(ApiError.BAD_REQUEST, writeResponseError(400))
        assertEquals(ApiError.UNAUTHORIZED, writeResponseError(401))
        assertEquals(ApiError.CONFLICT, writeResponseError(409))
        assertEquals(ApiError.WRITE_UNAVAILABLE, writeResponseError(503))
        assertEquals(ApiError.UNKNOWN_RESULT, writeResponseError(408))
        assertEquals(ApiError.UNKNOWN_RESULT, writeResponseError(504))
        assertEquals(ApiError.UNKNOWN_RESULT, writeResponseError(502, """{"error":"unknown_result"}"""))
        assertEquals(ApiError.WRITE_FAILED, writeResponseError(502, """{"error":"orca_failed"}"""))
    }

    @Test
    fun droppedConnectionIsUncertainAndNeverReplayed() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            exchange.requestBody.readBytes()
            calls.incrementAndGet()
        }) { api ->
            assertEquals(ApiError.UNKNOWN_RESULT, failureFrom { api.sendMessage("bot", "chat", "一回だけ") })
        }

        assertEquals(1, calls.get())
        assertFalse(OrcaApi.writeClient.retryOnConnectionFailure)
        assertTrue(writeBody("一回だけ").isOneShot())
    }

    @Test
    fun readTimeoutIsUncertainAndNeverReplayed() {
        val posts = AtomicInteger()
        val releaseResponse = CountDownLatch(1)
        val client = OrcaApi.writeClient.newBuilder().readTimeout(500, TimeUnit.MILLISECONDS).build()
        withServer({ exchange ->
            exchange.requestBody.readBytes()
            if (exchange.requestMethod == "POST" && posts.incrementAndGet() == 1) {
                releaseResponse.await(5, TimeUnit.SECONDS)
            } else {
                exchange.reply(202, """{"ok":true}""")
            }
        }, client) { api ->
            try {
                assertEquals(ApiError.UNKNOWN_RESULT, failureFrom { api.sendMessage("bot", "chat", "一回だけ") })
                assertEquals(1, posts.get())
            } finally {
                releaseResponse.countDown()
            }
        }
    }

    @Test
    fun retryAfterZeroDoesNotReplayPost() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            exchange.requestBody.readBytes()
            if (calls.incrementAndGet() == 1) exchange.responseHeaders.set("Retry-After", "0")
            exchange.reply(503, """{"error":"write_unavailable"}""")
        }) { api ->
            assertEquals(ApiError.WRITE_UNAVAILABLE, failureFrom { api.answer("bot", 7, "はい") })
        }

        assertEquals(1, calls.get())
    }

    @Test
    fun unusableSuccessReceiptIsUncertain() {
        withServer({ it.reply(202, """{"ok":false}""") }) { api ->
            assertEquals(ApiError.UNKNOWN_RESULT, failureFrom { api.sendMessage("bot", "chat", "確認") })
        }
    }

    @Test
    fun commandCatalogUsesReadErrorsExceptForUnpairedHost() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            if (calls.incrementAndGet() == 1) exchange.reply(200, """{"commands":[{"name":"compact","kind":"conversation"}],"skills":[{"name":"review"}],"options":{"model":{"choices":[{"value":"m","label":"Model M"}]}}}""")
            else exchange.reply(503, "{}")
        }) { api ->
            val catalog = api.commands("bot", "chat")
            assertEquals(SlashCommand("compact", kind = "conversation"), catalog.commands.single())
            assertEquals(ChatSkill("review"), catalog.skills.single())
            assertEquals(OptionChoice("m", "Model M"), catalog.options.model?.choices?.single())
            assertEquals(ApiError.WRITE_UNAVAILABLE, failureFrom { api.commands("bot", "chat") })
            assertEquals(ApiError.RESPONSE, failureFrom { api.bots() })
        }
    }

    @Test
    fun attachmentAndCommandWireFormat() {
        val requests = mutableListOf<Pair<String, String>>()
        var uploadHeaders = emptyList<String>()
        withServer({ e ->
            val path = e.requestURI.rawPath
            requests += (e.requestMethod + " " + path) to e.requestBody.bufferedReader().readText()
            when (path.substringAfterLast('/')) {
                "commands" -> e.reply(200, """{"commands":[],"skills":[]}""")
                "command" -> e.reply(200, """{"ok":true}""")
                "attachments" -> {
                    uploadHeaders = listOf("X-File-Name", "Content-Type", "Content-Length", "Authorization")
                        .map { e.requestHeaders.getFirst(it) }
                    e.reply(201, """{"path":"/store/file","name":"図 A+.txt","kind":"file","byteLength":3}""")
                }
                "messages" -> e.reply(202, """{"ok":true}""")
                else -> e.reply(201, """{"chatId":"new"}""")
            }
        }) { api ->
            api.commands("a/b", "chat")
            api.runCommand("a/b", "chat", "compact")
            api.runCommand("a/b", "chat", "model", "m")
            val receipt = api.uploadAttachment("a/b", "chat", "図 A+.txt", "text/plain", 3) { "abc".byteInputStream() }
            assertEquals(UploadedAttachment("/store/file", "図 A+.txt", "file", 3), receipt)
            val ref = listOf(AttachmentRef(receipt.path, receipt.name, receipt.kind))
            api.sendMessage("a/b", "chat", "確認", listOf(SkillRef("review", "/s")), ref)
            api.sendMessage("a/b", "chat", "", attachments = ref)
            assertEquals("new", api.startChat("a/b", "", listOf(InlineAttachment("図.txt", "text/plain", "YQ=="))))
        }
        val route = "/api/bots/a%2Fb/chats/chat"
        assertEquals(listOf("GET $route/commands", "POST $route/command", "POST $route/command",
            "POST $route/attachments", "POST $route/messages", "POST $route/messages", "POST /api/bots/a%2Fb/chats"),
            requests.map { it.first })
        assertEquals(listOf("%E5%9B%B3%20A%2B.txt", "text/plain", "3", "Bearer mock-token"), uploadHeaders)
        assertEquals(listOf("", """{"name":"compact"}""", """{"name":"model","argument":"m"}""", "abc",
            """{"text":"確認","skills":[{"name":"review","path":"/s"}],"attachments":[{"path":"/store/file","name":"図 A+.txt","kind":"file"}]}""",
            """{"text":"","attachments":[{"path":"/store/file","name":"図 A+.txt","kind":"file"}]}""",
            """{"text":"","inlineAttachments":[{"name":"図.txt","mimeType":"text/plain","contentBase64":"YQ=="}]}"""),
            requests.map { it.second })
    }

    @Test
    fun uploadErrorsDoNotReplayTheStream() {
        val calls = AtomicInteger()
        val opens = AtomicInteger()
        withServer({ e ->
            e.requestBody.readBytes()
            e.responseHeaders.set("Retry-After", "0")
            e.reply(if (calls.incrementAndGet() == 1) 400 else 503, "{}")
        }) { api ->
            for (expected in listOf(ApiError.BAD_REQUEST, ApiError.WRITE_UNAVAILABLE)) {
                assertEquals(expected, failureFrom {
                    api.uploadAttachment("bot", "chat", "a.txt", "text/plain", 1) {
                        opens.incrementAndGet()
                        "a".byteInputStream()
                    }
                })
            }
        }
        assertEquals(2, opens.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun onlyRejectedMessageDiscardsUploadReferencesForManualRetry() {
        val uploads = AtomicInteger()
        val messages = mutableListOf<String>()
        val files = listOf(LocalAttachment("content://test/a", "a", null, 1))
        val state = SavedStateHandle(mapOf("attachments" to apiJson.encodeToString(files)))
        val draft = ChatDraft(state, AttachmentLimits.Conversation)
        withServer({ exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            if (exchange.requestURI.path.endsWith("/attachments")) {
                exchange.reply(201, """{"path":"/ref${uploads.incrementAndGet()}","kind":"file"}""")
            } else {
                messages += body
                when (messages.size) {
                    1 -> exchange.reply(503, "{}")
                    2 -> exchange.reply(400, "{}")
                    else -> exchange.reply(202, """{"ok":true}""")
                }
            }
        }) { api ->
            suspend fun send(chat: ChatDraft) = chat.sendMessage(api, "bot", "chat", "確認") { "a".byteInputStream() }
            assertEquals(ApiError.WRITE_UNAVAILABLE, failureFrom { send(draft) })
            assertEquals("/ref1", draft.attachments.single().uploaded?.path)
            assertEquals(ApiError.BAD_REQUEST, failureFrom { send(draft) })
            val restored = ChatDraft(state, AttachmentLimits.Conversation)
            assertEquals(files, restored.attachments)
            send(restored)
        }
        assertEquals(2, uploads.get())
        assertEquals(3, messages.size)
        assertEquals(messages[0], messages[1])
        assertTrue(messages[2].contains("\"path\":\"/ref2\""))
    }

    private val apkBytes = byteArrayOf(0, 1, 2, -1)
    private val release = AppRelease(42, "0.2.42",
        MessageDigest.getInstance("SHA-256").digest(apkBytes).toByteString().hex(), apkBytes.size.toLong())

    @Test
    fun updateEndpointsFetchMetadataAndWriteExactApkBytes() {
        val requests = mutableListOf<List<String>>()
        withDownload({ exchange ->
            requests += listOf(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"))
            if (exchange.requestURI.path == "/api/app") {
                exchange.reply(200, """{"app":${apiJson.encodeToString(release)}}""")
            } else {
                exchange.replyApk()
            }
        }) { api, file ->
            val latest = requireNotNull(api.latestApp())
            assertEquals(release, latest)
            var progress = 0L
            api.downloadApp(latest, file) { progress = it }
            assertEquals(latest.size, progress)
            assertArrayEquals(apkBytes, file.readBytes())
        }
        assertEquals(listOf(
            listOf("GET", "/api/app", "Bearer mock-token"),
            listOf("GET", "/api/app/apk", "Bearer mock-token"),
        ), requests)
    }

    @Test
    fun updateRejectsMismatchedContentLength() {
        withDownload({ it.replyApk() }) { api, file ->
            val failure = runCatching { api.downloadApp(release.copy(size = release.size + 1), file) {} }.exceptionOrNull()
            assertTrue(failure is InvalidAppDownload)
            assertEquals(0L, file.length())
        }
    }

    @Test
    fun updateRejectsOversizedChunkedBody() {
        withDownload({ it.replyApk(contentLength = 0) }) { api, file ->
            val failure = runCatching { api.downloadApp(release.copy(size = release.size - 1), file) {} }.exceptionOrNull()
            assertTrue(failure is InvalidAppDownload)
        }
    }

    @Test
    fun updateRejectsMismatchedSha256() {
        withDownload({ it.replyApk() }) { api, file ->
            val failure = runCatching { api.downloadApp(release.copy(sha256 = "0".repeat(64)), file) {} }.exceptionOrNull()
            assertTrue(failure is InvalidAppDownload)
        }
    }

    @Test
    fun updateDownloadDoesNotFollowRedirects() {
        val routes = mutableListOf<String>()
        withDownload({ exchange ->
            routes += exchange.requestURI.path
            if (exchange.requestURI.path == "/api/app/apk") {
                exchange.responseHeaders.set("Location", "/redirected")
                exchange.sendResponseHeaders(302, -1)
            } else exchange.replyApk()
        }) { api, file ->
            val failure = runCatching { api.downloadApp(release, file) {} }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(0L, file.length())
        }
        assertEquals(listOf("/api/app/apk"), routes)
    }

    @Test
    fun updateMetadataAllowsNullRelease() {
        withServer({ it.reply(200, """{"app":null}""") }) { api -> assertNull(api.latestApp()) }
    }

    @Test
    fun updateMetadataRejectsInvalidRelease() {
        val invalid = release.copy(versionCode = 0)
        withServer({ it.reply(200, """{"app":${apiJson.encodeToString(invalid)}}""") }) { api ->
            assertEquals(ApiError.RESPONSE, failureFrom { api.latestApp() })
        }
    }

    @Test(timeout = 5000)
    fun updateDownloadResumesWithRuntimeExceptionFromResponseHandler() {
        val failure = IllegalStateException("progress handler failed")
        withDownload({ it.replyApk() }) { api, file ->
            val actual = runCatching { api.downloadApp(release, file) { throw failure } }.exceptionOrNull()
            assertSame(failure, actual)
        }
    }

    private fun HttpExchange.replyApk(contentLength: Long = apkBytes.size.toLong()) {
        sendResponseHeaders(200, contentLength)
        responseBody.write(apkBytes)
    }

    private fun withDownload(handle: (HttpExchange) -> Unit, test: suspend (OrcaApi, File) -> Unit) {
        val file = Files.createTempFile("orca-update-test-", ".apk")
        try {
            withServer(handle) { api -> test(api, file.toFile()) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun withServer(
        handle: (HttpExchange) -> Unit,
        client: OkHttpClient = OrcaApi.writeClient,
        test: suspend (OrcaApi) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use(handle) }
        server.start()
        try {
            val connection = requireNotNull(Connection.parse("http://127.0.0.1:${server.address.port}", "mock-token", allowLocalhost = true))
            runBlocking { test(OrcaApi(connection, client)) }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.reply(status: Int, json: String) {
        val data = json.toByteArray()
        sendResponseHeaders(status, data.size.toLong())
        responseBody.write(data)
    }

    private suspend fun failureFrom(action: suspend () -> Unit): ApiError {
        try {
            action()
        } catch (failure: ApiFailure) {
            return failure.error
        }
        error("Expected an API failure")
    }
}
