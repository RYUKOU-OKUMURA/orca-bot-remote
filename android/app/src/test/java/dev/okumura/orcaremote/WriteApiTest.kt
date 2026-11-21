package dev.okumura.orcaremote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
