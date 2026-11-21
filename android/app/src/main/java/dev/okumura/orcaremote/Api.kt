package dev.okumura.orcaremote

import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
data class Bots(val bots: List<Bot>)

@Serializable
data class Bot(
    val id: String,
    val name: String,
    val specialty: String? = null,
    val paused: Boolean,
    val needsYou: Int,
)

@Serializable
data class Chats(val chats: List<Chat>)

@Serializable
data class Chat(
    val id: String,
    val title: String? = null,
    val home: Boolean,
    val lastActivityAt: Long? = null,
    val lastAsked: String? = null,
    val lastSaid: String? = null,
)

@Serializable
data class Messages(val state: String? = null, val entries: List<Entry>)

@Serializable
sealed interface Entry {
    @Serializable
    @SerialName("message")
    data class Message(val role: String, val text: String) : Entry

    @Serializable
    @SerialName("tool")
    data class Tool(val name: String, val state: String? = null) : Entry
}

@Serializable
data class Items(val items: List<PendingItem>)

@Serializable
data class PendingItem(
    val bot: String,
    val number: Long,
    val title: String,
    val status: String,
    val chatId: String? = null,
    val lastActivityAt: Long? = null,
)

@Serializable
data class ItemDetail(
    val bot: String,
    val number: Long,
    val title: String,
    val status: String,
    val chatId: String? = null,
    val question: Question? = null,
)

@Serializable
data class Question(val text: String, val options: List<String>)

@Serializable
data class LatestSummary(val summary: Summary? = null)

@Serializable
data class Summary(val runAt: Long, val content: String, val bot: String, val chatId: String? = null)

internal val apiJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

@Serializable
internal data class NewChat(val chatId: String)

@Serializable
internal data class WriteReceipt(val ok: Boolean)

@Serializable
private data class WriteInput(val text: String)

@Serializable
private data class WriteError(val error: String)

internal class Connection private constructor(val url: HttpUrl, val token: String) {
    companion object {
        fun parse(url: String, token: String): Connection? {
            val server = url.trim().toHttpUrlOrNull() ?: return null
            if (server.username.isNotEmpty() || server.password.isNotEmpty() ||
                server.encodedPath != "/" || server.query != null || server.fragment != null
            ) return null
            val credential = token.trim()
            if (credential.isEmpty() || credential.any { it !in '!'..'~' }) return null
            return Connection(server, credential)
        }
    }
}

internal enum class ApiError(val text: String) {
    UNAUTHORIZED("トークンが違います。設定を確認してください"),
    CONNECTION("Mac mini につながりません（Tailscale がオンか確認）"),
    TIMEOUT("Mac mini の応答が遅れています。もう一度お試しください"),
    NOT_FOUND("対象が見つかりません"),
    ORCA("Orca の読み取りに失敗しました"),
    RESPONSE("サーバーの応答を読み取れませんでした"),
    BAD_REQUEST("1〜10,000文字で入力してください"),
    CONFLICT("状態が変わったため送れませんでした"),
    WRITE_FAILED("Bot に送れませんでした"),
    WRITE_UNAVAILABLE("Mac mini の書き込み設定が済んでいません。"),
    UNKNOWN_RESULT("送れたか確認できませんでした。会話を更新して確かめてください。"),
}

internal class ApiFailure(val error: ApiError) : Exception()

internal class OrcaApi(private val connection: Connection) {
    suspend fun bots(): Bots = get("bots")
    suspend fun chats(bot: String): Chats = get("bots", bot, "chats")
    suspend fun messages(bot: String, chatId: String): Messages =
        get("bots", bot, "chats", chatId, "messages", limit = 100)
    suspend fun items(): Items = get("items")
    suspend fun item(bot: String, number: Long): ItemDetail = get("items", bot, number.toString())
    suspend fun summary(): LatestSummary = get("summary")

    suspend fun startChat(bot: String, text: String): String {
        val result = post<NewChat>(201, text, "bots", bot, "chats")
        if (result.chatId.isBlank()) throw ApiFailure(ApiError.UNKNOWN_RESULT)
        return result.chatId
    }

    suspend fun sendMessage(bot: String, chatId: String, text: String) {
        val result = post<WriteReceipt>(202, text, "bots", bot, "chats", chatId, "messages")
        if (!result.ok) throw ApiFailure(ApiError.UNKNOWN_RESULT)
    }

    suspend fun answer(bot: String, number: Long, text: String) {
        val result = post<WriteReceipt>(200, text, "items", bot, number.toString(), "answer")
        if (!result.ok) throw ApiFailure(ApiError.UNKNOWN_RESULT)
    }

    private fun request(path: Array<out String>, limit: Int? = null): Request.Builder {
        val url = connection.url.newBuilder().addPathSegment("api")
        path.forEach { url.addPathSegment(it) }
        if (limit != null) url.addQueryParameter("limit", limit.toString())
        return Request.Builder().url(url.build())
            .header("Authorization", "Bearer ${connection.token}")
    }

    private suspend inline fun <reified T> get(vararg path: String, limit: Int? = null): T {
        val request = request(path, limit).build()
        return try {
            val body = client.newCall(request).readBody()
            withContext(Dispatchers.Default) { apiJson.decodeFromString(body) }
        } catch (_: InterruptedIOException) {
            throw ApiFailure(ApiError.TIMEOUT)
        } catch (_: IOException) {
            throw ApiFailure(ApiError.CONNECTION)
        } catch (_: SerializationException) {
            throw ApiFailure(ApiError.RESPONSE)
        }
    }

    private suspend inline fun <reified T> post(status: Int, text: String, vararg path: String): T {
        val request = request(path).post(writeBody(text)).build()
        return try {
            val body = writeClient.newCall(request).readBody(status)
            withContext(Dispatchers.Default) { apiJson.decodeFromString(body) }
        } catch (_: IOException) {
            throw ApiFailure(ApiError.UNKNOWN_RESULT)
        } catch (_: SerializationException) {
            throw ApiFailure(ApiError.UNKNOWN_RESULT)
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            // The server may run three 20-second Orca commands per request.
            .readTimeout(65, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build()
        internal val writeClient = client.newBuilder().retryOnConnectionFailure(false).build()
    }
}

internal fun writeBody(text: String): RequestBody {
    val body = apiJson.encodeToString(WriteInput(text)).toRequestBody("application/json".toMediaType())
    // Even a response-driven follow-up must never replay the owner's input.
    return object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
        override fun isOneShot() = true
    }
}

internal fun writeResponseError(status: Int, body: String = ""): ApiError = when (status) {
    400 -> ApiError.BAD_REQUEST
    401 -> ApiError.UNAUTHORIZED
    404 -> ApiError.NOT_FOUND
    409 -> ApiError.CONFLICT
    502 -> {
        val code = try {
            apiJson.decodeFromString<WriteError>(body).error
        } catch (_: SerializationException) {
            null
        }
        if (code == "orca_failed") ApiError.WRITE_FAILED else ApiError.UNKNOWN_RESULT
    }
    503 -> ApiError.WRITE_UNAVAILABLE
    else -> ApiError.UNKNOWN_RESULT
}

private suspend fun Call.readBody(writeStatus: Int? = null): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val body = response.use {
                    if (writeStatus != null && it.code != writeStatus) {
                        val errorBody = if (it.code == 502) it.body.string() else ""
                        throw ApiFailure(writeResponseError(it.code, errorBody))
                    }
                    if (writeStatus == null && !it.isSuccessful) {
                        throw ApiFailure(when (it.code) {
                            401 -> ApiError.UNAUTHORIZED
                            404 -> ApiError.NOT_FOUND
                            502 -> ApiError.ORCA
                            else -> ApiError.RESPONSE
                        })
                    }
                    it.body.string()
                }
                continuation.resume(body)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    })
}
