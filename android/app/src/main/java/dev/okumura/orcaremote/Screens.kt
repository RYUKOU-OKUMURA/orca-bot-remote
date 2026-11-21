package dev.okumura.orcaremote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private val ListPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(
    title: String,
    back: (() -> Unit)? = null,
    action: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (back != null) TextButton(onClick = back) { Text("‹ 戻る") }
                },
                actions = { action() },
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
    ) { padding ->
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            content(Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun <T> screenModel(key: String? = null, fetch: suspend () -> T): ScreenModel<T> {
    val model: ScreenModel<T> = viewModel(key = key) { ScreenModel(fetch) }
    LaunchedEffect(model) {
        if (model.data == null && model.error == null) model.refresh()
    }
    return model
}

@Composable
private fun <T> DataContent(
    modifier: Modifier,
    model: ScreenModel<T>,
    settings: () -> Unit,
    keepDataOnError: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val error = model.error
    val data = model.data
    Box(modifier) {
        when {
            error != null && (!keepDataOnError || data == null) -> {
                LoadError(model, error, settings, Modifier.align(Alignment.Center))
            }
            data != null && keepDataOnError -> Column {
                if (error != null) {
                    Text(
                        error.text,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (error == ApiError.UNAUTHORIZED) {
                        TextButton(onClick = settings) { Text("設定を開く") }
                    }
                }
                Box(Modifier.weight(1f)) { content(data) }
            }
            data != null -> content(data)
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (error != null && model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun LoadError(
    model: ScreenModel<*>,
    error: ApiError,
    settings: () -> Unit,
    modifier: Modifier,
    retry: () -> Unit = model::refresh,
) {
    val retryWithPermission = withLocalNetworkPermission(onDenied = {}, onGranted = retry)
    Column(
        modifier.widthIn(max = 450.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(error.text, color = MaterialTheme.colorScheme.error)
        when (error) {
            ApiError.UNAUTHORIZED -> Button(onClick = settings) { Text("設定を開く") }
            else -> Button(
                onClick = if (error == ApiError.CONNECTION) retryWithPermission else retry,
                enabled = !model.loading,
            ) { Text("再試行") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    api: OrcaApi,
    showSuccess: Boolean,
    successShown: () -> Unit,
    settings: () -> Unit,
    openBot: (String) -> Unit,
    openItems: () -> Unit,
    openSummary: () -> Unit,
) {
    val bots = screenModel("bots") { api.bots().bots }
    val items = screenModel("items") { api.items().items.size }
    val summary = screenModel("summary") { api.summary() }
    val refresh = {
        bots.refresh()
        items.refresh()
        summary.refresh()
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            snackbar.showSnackbar("接続できました。設定を保存しました")
            successShown()
        }
    }
    Page(
        "Orca Bot Remote",
        action = { TextButton(onClick = settings) { Text("設定") } },
        snackbar = snackbar,
    ) { modifier ->
        val botError = bots.error
        if (botError != null) {
            Box(modifier) {
                LoadError(bots, botError, settings, Modifier.align(Alignment.Center), retry = refresh)
                if (bots.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            PullToRefreshBox(
                isRefreshing = bots.loading || items.loading || summary.loading,
                onRefresh = refresh,
                modifier = modifier,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().semantics {
                        customActions = listOf(CustomAccessibilityAction("更新") {
                            refresh()
                            true
                        })
                    },
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        RowCard(
                            title = "12:00のまとめ",
                            caption = if (summary.error != null) "まとめを読めませんでした" else null,
                            subtitle = summary.data?.let { result ->
                                result.summary?.content?.let(::summaryPreview) ?: "まだありません"
                            },
                            onClick = openSummary,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    item {
                        val color = MaterialTheme.colorScheme
                        val count = items.data
                        RowCard(
                            title = if (count == null) "判断待ち" else "判断待ち ${count}件",
                            caption = if (items.error != null) "件数を読めませんでした" else null,
                            onClick = openItems,
                            containerColor = if (count == 0) color.surfaceContainer else color.secondaryContainer,
                            contentColor = if (count == 0) color.onSurfaceVariant else color.onSecondaryContainer,
                        )
                    }
                    item {
                        Text(
                            "Bot一覧",
                            modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    val botList = bots.data
                    if (botList?.isEmpty() == true) item { Text("Bot が見つかりません") }
                    items(botList.orEmpty(), key = { it.id }) { bot ->
                        RowCard(
                            title = bot.name,
                            subtitle = listOfNotNull(bot.specialty?.takeIf { it.isNotBlank() }, "停止中".takeIf { bot.paused })
                                .joinToString(" · ").ifEmpty { null },
                            badge = "判断待ち ${bot.needsYou}件".takeIf { bot.needsYou > 0 },
                            onClick = { openBot(bot.name) },
                        )
                    }
                }
            }
        }
    }
}

private fun summaryPreview(content: String): String =
    content.lineSequence().map { it.trim().trimStart('#', '-', ' ').trim() }
        .firstOrNull { it.isNotEmpty() }.orEmpty()

internal val Chat.displayTitle: String
    get() = title?.takeIf { it.isNotBlank() } ?: if (home) "ホームチャット" else "タイトルなし"

@Composable
internal fun ChatsScreen(
    api: OrcaApi,
    bot: String,
    back: () -> Unit,
    settings: () -> Unit,
    openChat: (Chat) -> Unit,
) {
    val model = screenModel { api.chats(bot) }
    Page(bot, back, action = { RefreshAction(model) }) { modifier ->
        DataContent(modifier, model, settings) { result ->
            EntryList(empty = result.chats.isEmpty(), emptyText = "チャットはまだありません") {
                items(result.chats.sortedByDescending { it.home }, key = { it.id }) { chat ->
                    RowCard(
                        title = chat.displayTitle,
                        subtitle = chat.lastSaid?.takeIf { it.isNotBlank() } ?: chat.lastAsked,
                        caption = relativeTime(chat.lastActivityAt),
                        badge = "ホーム".takeIf { chat.home },
                        onClick = { openChat(chat) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConversationScreen(
    api: OrcaApi,
    bot: String,
    chatId: String,
    title: String,
    back: () -> Unit,
    settings: () -> Unit,
) {
    val model = screenModel { api.messages(bot, chatId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val polling = shouldPoll(model.data?.state, model.error)
    LaunchedEffect(lifecycle, model, polling) {
        if (polling) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(5_000)
                if (!shouldPoll(model.data?.state, model.error)) break
                model.load()
            }
        }
    }
    Page(title, back, action = { RefreshAction(model) }) { modifier ->
        DataContent(modifier, model, settings, keepDataOnError = true) { result ->
            if (result.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("メッセージはまだありません")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(result.entries.asReversed()) { entry ->
                        when (entry) {
                            is Entry.Message -> MessageBubble(entry)
                            is Entry.Tool -> Text(
                                "🔧 ${entry.name}",
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Entry.Message) {
    val presentation = remember(message) { presentMessage(message.role, message.text) }
    val owner = presentation.kind == MessageKind.OWNER
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (owner) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(0.88f),
            shape = MaterialTheme.shapes.large,
            color = when (presentation.kind) {
                MessageKind.OWNER -> colors.primaryContainer
                MessageKind.MAIL -> colors.surface
                MessageKind.OTHER -> colors.surfaceContainer
            },
            contentColor = if (owner) colors.onPrimaryContainer else colors.onSurface,
            border = if (presentation.kind == MessageKind.MAIL) BorderStroke(1.dp, colors.outlineVariant) else null,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(presentation.heading, style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        presentation.subject?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        Text(presentation.body)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ItemsScreen(
    api: OrcaApi,
    back: () -> Unit,
    settings: () -> Unit,
    openChat: (String, String, String) -> Unit,
    openItem: (PendingItem) -> Unit,
) {
    val model = screenModel { api.items() }
    Page("判断待ち", back, action = { RefreshAction(model) }) { modifier ->
        DataContent(modifier, model, settings) { result ->
            EntryList(empty = result.items.isEmpty(), emptyText = "判断待ちはありません") {
                items(result.items, key = { "${it.bot}/${it.number}" }) { item ->
                    Column {
                        RowCard(
                            title = item.title,
                            subtitle = item.bot,
                            caption = relativeTime(item.lastActivityAt),
                            onClick = { openItem(item) },
                        )
                        item.chatId?.let { chatId ->
                            TextButton(onClick = { openChat(item.bot, chatId, item.title) }) {
                                Text("このチャットを開く")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ItemScreen(
    api: OrcaApi,
    bot: String,
    number: Long,
    back: () -> Unit,
    settings: () -> Unit,
    openChat: (String, String, String) -> Unit,
) {
    val model = screenModel { api.item(bot, number) }
    Page("判断待ちの詳細", back, action = { RefreshAction(model) }) { modifier ->
        DataContent(modifier, model, settings) { item ->
            DetailContent {
                Text(bot, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(item.title, style = MaterialTheme.typography.headlineSmall)
                val question = item.question
                if (question == null) {
                    Text("質問はありません")
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(question.text)
                            question.options.forEach { Text("•  $it") }
                        }
                    }
                }
                item.chatId?.let { chatId ->
                    Button(onClick = { openChat(item.bot, chatId, item.title) }) { Text("このチャットを開く") }
                }
            }
        }
    }
}

@Composable
internal fun SummaryScreen(
    api: OrcaApi,
    back: () -> Unit,
    settings: () -> Unit,
    openChat: (String, String, String) -> Unit,
) {
    val model = screenModel { api.summary() }
    Page("12:00のまとめ", back, action = { RefreshAction(model) }) { modifier ->
        DataContent(modifier, model, settings) { result ->
            DetailContent {
                val summary = result.summary
                if (summary == null) {
                    Text("まだありません")
                } else {
                    Text(summary.bot, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(summary.content) }
                    summary.chatId?.let { chatId ->
                        Button(onClick = { openChat(summary.bot, chatId, summary.bot) }) {
                            Text("このチャットを開く")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun EntryList(
    empty: Boolean,
    emptyText: String,
    content: LazyListScope.() -> Unit,
) {
    if (empty) {
        Text(emptyText, Modifier.padding(20.dp))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun RefreshAction(model: ScreenModel<*>) {
    TextButton(onClick = model::refresh, enabled = !model.loading) { Text("更新") }
}

@Composable
private fun RowCard(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    caption: String? = null,
    badge: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            badge?.let {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(it, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            subtitle?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
