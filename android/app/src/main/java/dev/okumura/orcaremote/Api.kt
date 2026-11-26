package dev.okumura.orcaremote

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URLEncoder
import java.net.Proxy
import java.net.ProtocolException
import java.security.MessageDigest
import java.net.UnknownHostException
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
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import okio.source
import kotlinx.serialization.json.encodeToStream

@Serializable
data class Bots(val bots: List<Bot>)

@Serializable
data class Bot(
    val id: String,
    val name: String,
    val specialty: String? = null,
    val paused: Boolean = false,
    val needsYou: Int = 0,
    val avatar: String? = null,
    val lastActivityAt: Long? = null,
    val lastSaid: String? = null,
    val working: Boolean = false,
    val activeWork: String? = null,
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
    val working: Boolean = false,
)

@Serializable
data class Messages(val state: String? = null, val entries: List<Entry>, val activeWorkers: List<ActiveWorker> = emptyList())

@Serializable
data class ActiveWorker(val title: String, val tool: String? = null)

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
    val avatar: String? = null,
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
data class Summary(val runAt: Long? = null, val content: String, val bot: String, val chatId: String? = null)

internal val apiJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

@Serializable
internal data class NewChat(val chatId: String)

@Serializable
internal data class OkReceipt(val ok: Boolean)

@Serializable
data class ChatCommands(val commands: List<SlashCommand>, val skills: List<ChatSkill>, val options: ChatOptions = ChatOptions())

@Serializable
data class SlashCommand(val name: String, val description: String = "", val kind: String, val argumentHint: String? = null)

@Serializable
data class ChatSkill(val name: String, val description: String = "", val path: String? = null, val argumentHint: String? = null)

@Serializable
data class ChatOptions(val model: OptionDescriptor? = null, val effort: OptionDescriptor? = null)

@Serializable
data class OptionDescriptor(val current: String? = null, val choices: List<OptionChoice> = emptyList())

@Serializable
data class OptionChoice(val value: String, val label: String)

@Serializable
data class BotOptions(
    val models: List<BotModelOption> = emptyList(),
    val defaultModel: String? = null,
    val defaultEffort: String? = null,
    val agents: List<BotAgentOption> = emptyList(),
    val agent: String? = null,
    val defaultAgent: String? = null,
)

@Serializable
data class BotAgentOption(val id: String, val label: String, val isDefault: Boolean = false)

@Serializable
data class BotModelOption(val id: String, val label: String, val isDefault: Boolean = false, val efforts: List<OptionChoice> = emptyList())

@Serializable
data class MemoTask(
    val id: String,
    val title: String,
    val note: String? = null,
    val due: String? = null,
    val bot: String? = null,
    val done: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val doneAt: Long? = null,
)

@Serializable
data class MemoTasks(val tasks: List<MemoTask>)

@Serializable
internal data class NewTask(val title: String, val note: String? = null, val due: String? = null, val bot: String? = null)

// No defaults: null is sent explicitly so the server clears the field.
@Serializable
internal data class TaskEdit(val title: String, val note: String?, val due: String?, val bot: String?)

@Serializable
private data class TaskDone(val done: Boolean)

// No default: null is sent so the server clears the due date.
@Serializable
private data class TaskDue(val due: String?)

@Serializable
private data class TaskReceipt(val task: MemoTask)

@Serializable
data class SkillRef(val name: String, val path: String)

@Serializable
data class UploadedAttachment(val path: String, val name: String? = null, val kind: String, val byteLength: Long? = null)

@Serializable
data class AttachmentRef(val path: String, val name: String? = null, val kind: String)

@Serializable
data class InlineAttachment(val name: String, val mimeType: String? = null, val contentBase64: String)

@Serializable
private data class WriteInput(
    val text: String,
    val skills: List<SkillRef> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
    val inlineAttachments: List<InlineAttachment> = emptyList(),
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

@Serializable
internal data class CommandInput(val name: String, val argument: String? = null)

@Serializable
private data class WriteError(val error: String)

internal class Connection private constructor(val url: HttpUrl, val token: String) {
    companion object {
        private val tailscaleName = Regex("""(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+ts\.net""")

        private fun allowsHttp(host: String, allowLocalhost: Boolean): Boolean {
            if (allowLocalhost && host in setOf("localhost", "127.0.0.1", "10.0.2.2")) return true
            // HttpUrl validates IPv6 literals and returns their canonical, unbracketed form.
            if (host.startsWith("fd7a:115c:a1e0:")) return '%' !in host
            if (host.length <= 253 && tailscaleName.matches(host)) return true
            val octets = host.split('.')
            return octets.size == 4 && octets[0] == "100" && octets[1].toIntOrNull() in 64..127 &&
                octets.all { it.toIntOrNull() in 0..255 && it == it.toInt().toString() }
        }

        fun parse(url: String, token: String, allowLocalhost: Boolean = BuildConfig.DEBUG): Connection? {
            val server = url.trim().toHttpUrlOrNull() ?: return null
            if (!server.isHttps && !allowsHttp(server.host, allowLocalhost)) return null
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
    TASKS("タスクリストを読み書きできませんでした"),
}

internal class ApiFailure(val error: ApiError) : Exception()

@Serializable
internal data class AppRelease(val versionCode: Long, val versionName: String, val sha256: String, val size: Long)

@Serializable
private data class LatestApp(val app: AppRelease?)

private val apkSha256 = Regex("[a-f0-9]{64}")

internal fun validRelease(release: AppRelease): Boolean =
    release.versionCode in 1..9_007_199_254_740_991L &&
        release.versionName.length in 1..64 && release.versionName.all { it in ' '..'~' } &&
        apkSha256.matches(release.sha256) && release.size in 1..(200L * 1024 * 1024)

internal fun isNewerRelease(release: AppRelease, current: Long) = release.versionCode > current

internal fun matchesDownload(release: AppRelease, size: Long, sha256: String) =
    size == release.size && sha256 == release.sha256

internal fun matchesAppArchive(release: AppRelease, expectedPackage: String, archivePackage: String?, archiveVersion: Long?) =
    expectedPackage == archivePackage && release.versionCode == archiveVersion

internal class InvalidAppDownload : IOException()

internal class OrcaApi(
    private val connection: Connection,
    private val postClient: OkHttpClient = writeClient,
) {
    suspend fun health() {
        if (!get<OkReceipt>("health").ok) throw ApiFailure(ApiError.RESPONSE)
    }


    suspend fun latestApp(): AppRelease? = get<LatestApp>("app").app?.also {
        if (!validRelease(it)) throw ApiFailure(ApiError.RESPONSE)
    }

    suspend fun downloadApp(release: AppRelease, file: File, onProgress: (Long) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            val call = updateClient.newCall(request("app", "apk").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) return
                            if (!response.isSuccessful) throw IOException("APK download failed")
                            val body = response.body
                            val length = body.contentLength()
                            if (length != -1L && length != release.size) throw InvalidAppDownload()
                            val hash = MessageDigest.getInstance("SHA-256")
                            var bytes = 0L
                            file.outputStream().buffered().use { output ->
                                val input = body.byteStream()
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    bytes += count
                                    if (bytes > release.size) throw InvalidAppDownload()
                                    hash.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    onProgress(bytes)
                                }
                            }
                            if (!matchesDownload(release, bytes, hash.digest().toByteString().hex())) {
                                throw InvalidAppDownload()
                            }
                        }
                        Result.success(Unit)
                    } catch (failure: Exception) {
                        Result.failure(if (failure is ProtocolException) InvalidAppDownload() else failure)
                    } finally {
                        if (call.isCanceled()) file.delete()
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    fun eventsCall(): Call = eventsClient.newCall(request("events").header("Accept", "text/event-stream").build())

    suspend fun bots(): Bots = get("bots")
    suspend fun chats(bot: String): Chats = get("bots", bot, "chats")
    suspend fun messages(bot: String, chatId: String): Messages =
        get("bots", bot, "chats", chatId, "messages", query = "limit" to "100")
    suspend fun board(): BoardSnapshot? = try {
        get<BoardSnapshot>("board")
    } catch (failure: ApiFailure) {
        if (failure.error == ApiError.NOT_FOUND) null else throw failure
    }
    suspend fun items(): Items = get("items")
    suspend fun item(bot: String, number: Long): ItemDetail = get("items", bot, number.toString())
    suspend fun summary(): LatestSummary = get("summary")

    suspend fun commands(bot: String, chatId: String): ChatCommands =
        get("bots", bot, "chats", chatId, "commands", commands = true)

    suspend fun runCommand(bot: String, chatId: String, name: String, argument: String? = null) {
        val body = jsonBody(apiJson.encodeToString(CommandInput(name, argument)))
        val result = post<OkReceipt>(200, request("bots", bot, "chats", chatId, "command").post(body))
        if (!result.ok) throw ApiFailure(ApiError.UNKNOWN_RESULT)
    }

    suspend fun uploadAttachment(
        bot: String, chatId: String, fileName: String, contentType: String, contentLength: Long,
        openStream: () -> InputStream,
    ): UploadedAttachment {
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = contentLength
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                openStream().source().use { sink.writeAll(it) }
            }
        }
        val name = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
        return post(201, request("bots", bot, "chats", chatId, "attachments")
            .header("X-File-Name", name).post(body))
    }

    suspend fun botOptions(bot: String, agent: String? = null): BotOptions =
        get("bots", bot, "options", query = agent?.let { "agent" to it }, commands = true)

    suspend fun tasks(): MemoTasks = get("tasks")

    suspend fun createTask(task: NewTask): MemoTask =
        post<TaskReceipt>(201, request("tasks").post(jsonBody(apiJson.encodeToString(task)))).task

    suspend fun editTask(id: String, edit: TaskEdit): MemoTask =
        post<TaskReceipt>(200, request("tasks", id).patch(jsonBody(apiJson.encodeToString(edit)))).task

    suspend fun setTaskDone(id: String, done: Boolean): MemoTask =
        post<TaskReceipt>(200, request("tasks", id).patch(jsonBody(apiJson.encodeToString(TaskDone(done))))).task

    suspend fun setTaskDue(id: String, due: String?): MemoTask =
        post<TaskReceipt>(200, request("tasks", id).patch(jsonBody(apiJson.encodeToString(TaskDue(due))))).task

    suspend fun deleteTask(id: String) {
        if (!post<OkReceipt>(200, request("tasks", id).delete()).ok) throw ApiFailure(ApiError.UNKNOWN_RESULT)
    }

    suspend fun startChat(
        bot: String, text: String, inlineAttachments: List<InlineAttachment> = emptyList(),
        model: String? = null, effort: String? = null, agent: String? = null,
    ): String {
        val input = WriteInput(text, inlineAttachments = inlineAttachments, agent = agent, model = model, effort = effort)
        val body = if (inlineAttachments.isEmpty()) jsonBody(apiJson.encodeToString(input)) else inlineBody(input)
        val result = post<NewChat>(201, request("bots", bot, "chats").post(body))
        if (result.chatId.isBlank()) throw ApiFailure(ApiError.UNKNOWN_RESULT)
        return result.chatId
    }

    suspend fun sendMessage(
        bot: String, chatId: String, text: String,
        skills: List<SkillRef> = emptyList(), attachments: List<AttachmentRef> = emptyList(),
    ) {
        val body = jsonBody(apiJson.encodeToString(WriteInput(text, skills, attachments)))
        val result = post<OkReceipt>(202, request("bots", bot, "chats", chatId, "messages").post(body))
        if (!result.ok) throw ApiFailure(ApiError.UNKNOWN_RESULT)
    }

    suspend fun answer(bot: String, number: Long, text: String) {
        val result = post<OkReceipt>(200, request("items", bot, number.toString(), "answer").post(writeBody(text)))
        if (!result.ok) throw ApiFailure(ApiError.UNKNOWN_RESULT)
    }

    private fun request(vararg path: String, query: Pair<String, String>? = null): Request.Builder {
        val url = connection.url.newBuilder().addPathSegment("api")
        path.forEach { url.addPathSegment(it) }
        if (query != null) url.addQueryParameter(query.first, query.second)
        return Request.Builder().url(url.build())
            .header("Authorization", "Bearer ${connection.token}")
    }

    private suspend inline fun <reified T> get(vararg path: String, query: Pair<String, String>? = null, commands: Boolean = false): T {
        val request = request(*path, query = query).build()
        return try {
            val body = client.newCall(request).readBody(commands = commands)
            withContext(Dispatchers.Default) { apiJson.decodeFromString(body) }
        } catch (_: InterruptedIOException) {
            throw ApiFailure(ApiError.TIMEOUT)
        } catch (_: IOException) {
            throw ApiFailure(ApiError.CONNECTION)
        } catch (_: SerializationException) {
            throw ApiFailure(ApiError.RESPONSE)
        }
    }

    private suspend inline fun <reified T> post(status: Int, request: Request.Builder): T {
        return try {
            val body = postClient.newCall(request.build()).readBody(status)
            withContext(Dispatchers.Default) { apiJson.decodeFromString(body) }
        } catch (e: IOException) {
            throw ApiFailure(when (e) {
                is ConnectException, is UnknownHostException, is NoRouteToHostException -> ApiError.CONNECTION
                else -> ApiError.UNKNOWN_RESULT
            })
        } catch (_: SerializationException) {
            throw ApiFailure(ApiError.UNKNOWN_RESULT)
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(5, 50, TimeUnit.SECONDS))
            .followRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            // The server may run three 20-second Orca commands per request.
            .readTimeout(65, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build()
        private val eventsClient = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private val updateClient = client.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        internal val writeClient = client.newBuilder()
            .retryOnConnectionFailure(false)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

// Base64 is ASCII without JSON escapes. Measure metadata only and stream the large JSON on OkHttp's thread.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun inlineBody(input: WriteInput) = object : RequestBody() {
    private val metadata = input.copy(inlineAttachments = input.inlineAttachments.map { it.copy(contentBase64 = "") })
    override fun contentType() = "application/json".toMediaType()
    override fun contentLength() = apiJson.encodeToString(metadata).toByteArray().size +
        input.inlineAttachments.sumOf { it.contentBase64.length.toLong() }
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = apiJson.encodeToStream(input, sink.outputStream())
}

internal fun writeBody(text: String): RequestBody = jsonBody(apiJson.encodeToString(WriteInput(text)))

private fun jsonBody(json: String): RequestBody {
    val body = json.toRequestBody("application/json".toMediaType())
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
    500 -> ApiError.TASKS
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

private suspend fun Call.readBody(writeStatus: Int? = null, commands: Boolean = false): String = suspendCancellableCoroutine { continuation ->
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
                            500 -> ApiError.TASKS
                            502 -> ApiError.ORCA
                            503 -> if (commands) ApiError.WRITE_UNAVAILABLE else ApiError.RESPONSE
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
