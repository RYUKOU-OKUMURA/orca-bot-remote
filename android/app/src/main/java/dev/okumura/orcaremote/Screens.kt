package dev.okumura.orcaremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ListPadding = PaddingValues(horizontal = OrcaInset, vertical = OrcaGap)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(
    title: String,
    back: (() -> Unit)? = null,
    action: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    container: Color = MaterialTheme.colorScheme.background,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = container,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            Column {
                TopAppBar(
                    expandedHeight = 56.dp,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = container),
                    title = {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                    navigationIcon = {
                        if (back != null) {
                            IconButton(onClick = back) { OrcaIcon(R.drawable.ic_back, "戻る") }
                        }
                    },
                    actions = { action() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
    ) { padding ->
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
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
                if (error != null) RefreshError(error, settings)
                Box(Modifier.weight(1f)) { content(data) }
            }
            data != null -> content(data)
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (error != null && model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun RefreshError(error: ApiError, settings: () -> Unit) {
    Text(
        error.text,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    if (error == ApiError.UNAUTHORIZED) {
        OrcaTextButton(onClick = settings) { Text("設定を開く") }
    }
}

@Composable
private fun LoadError(
    model: ScreenModel<*>,
    error: ApiError,
    settings: () -> Unit,
    modifier: Modifier,
    retry: () -> Unit = { model.refresh() },
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
                shape = MaterialTheme.shapes.medium,
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
    meta: ChatMetaStore,
    showSuccess: Boolean,
    successShown: () -> Unit,
    refreshCounts: Boolean,
    countsRefreshStarted: () -> Unit,
    settings: () -> Unit,
    openBot: (String) -> Unit,
    openItems: () -> Unit,
    openSummary: () -> Unit,
    openChat: (String, String, String) -> Unit,
) {
    val bots = screenModel("bots") { api.bots().bots.also(meta::observeBots) }
    val summary = screenModel("summary") { api.summary() }
    var expandedBot by rememberSaveable { mutableStateOf<String?>(null) }
    val inlineChats = expandedBot?.let { bot ->
        viewModel<ScreenModel<Chats>>(key = "inline chats: $bot") { ScreenModel { api.chats(bot).also { meta.observeChats(bot, it.chats) } } }
    }
    LaunchedEffect(inlineChats) {
        inlineChats?.load()
    }
    val refresh = {
        bots.refresh()
        summary.refresh()
        inlineChats?.refresh()
        Unit
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(refreshCounts) {
        if (refreshCounts) {
            bots.refresh(afterCurrent = true)
            countsRefreshStarted()
        }
    }
    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            snackbar.showSnackbar("接続できました。設定を保存しました")
            successShown()
        }
    }
    Page(
        "Bots",
        action = { IconButton(onClick = settings) { OrcaIcon(R.drawable.ic_settings, "設定") } },
        snackbar = snackbar,
        container = MaterialTheme.colorScheme.surfaceContainer,
    ) { modifier ->
        val botError = bots.error
        if (botError != null && bots.data == null) {
            Box(modifier) {
                LoadError(bots, botError, settings, Modifier.align(Alignment.Center), retry = refresh)
                if (bots.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            return@Page
        }
        Column(modifier) {
            if (botError != null) RefreshError(botError, settings)
            PullToRefreshBox(
                isRefreshing = bots.loading || summary.loading,
                onRefresh = refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().semantics {
                        customActions = listOf(CustomAccessibilityAction("更新") {
                            refresh()
                            true
                        })
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    val botList = bots.data
                    if (botList?.isEmpty() == true) item { Text("Bot が見つかりません", Modifier.padding(OrcaInset)) }
                    botList.orEmpty().forEach { bot ->
                        val expanded = expandedBot == bot.name
                        item(key = "bot/${bot.id}") {
                            BotRow(
                                bot, unread = meta.unread(bot),
                                expanded = expanded,
                                open = { openBot(bot.name) },
                                toggle = { expandedBot = if (expanded) null else bot.name },
                            )
                            if (expanded && inlineChats != null) {
                                when {
                                    inlineChats.error == ApiError.UNAUTHORIZED -> OrcaTextButton(
                                        onClick = settings,
                                        modifier = Modifier.padding(start = 48.dp),
                                    ) { Text("設定を開く") }
                                    inlineChats.error != null -> OrcaTextButton(
                                        onClick = { openBot(bot.name) },
                                        modifier = Modifier.padding(start = 48.dp),
                                    ) { Text("チャット一覧を開く") }
                                    inlineChats.data == null -> LinearProgressIndicator(
                                        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
                                    )
                                    inlineChats.data?.chats?.isEmpty() == true -> Text(
                                        "チャットはまだありません",
                                        Modifier.padding(start = 56.dp, top = 8.dp, bottom = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        if (expanded && inlineChats?.error == null) {
                            items(
                                meta.sorted(bot.name, inlineChats?.data?.chats.orEmpty()),
                                key = { "chat/${bot.id}/${it.id}" },
                            ) { chat ->
                                ChatRow(chat, bot.name, meta, Modifier.padding(start = 40.dp), showPreview = false) {
                                    openChat(bot.name, chat.id, chat.displayTitle)
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        ListRow(
                            title = "12:00のまとめ",
                            onClick = openSummary,
                            leading = { OrcaIcon(R.drawable.ic_summary) },
                            subtitle = if (summary.error != null) "まとめを読めませんでした" else summary.data?.let { result ->
                                markdownPreview(result.summary?.content) ?: "まだありません"
                            },
                        )
                    }
                    item {
                        val count = botList?.sumOf { it.needsYou }
                        ListRow(
                            title = if (count == null) "判断待ち" else "判断待ち ${count}件",
                            onClick = openItems,
                            leading = {
                                OrcaIcon(
                                    R.drawable.ic_pending,
                                    tint = if ((count ?: 0) > 0) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

internal val Chat.displayTitle: String
    get() = if (home) "Home Chat" else title?.takeIf { it.isNotBlank() } ?: "タイトルなし"

@Composable
internal fun ChatsScreen(
    api: OrcaApi,
    meta: ChatMetaStore,
    bot: String,
    back: () -> Unit,
    settings: () -> Unit,
    refreshChats: Boolean,
    chatsRefreshFinished: () -> Unit,
    openNewChat: () -> Unit,
    openChat: (Chat) -> Unit,
) {
    val model = screenModel { api.chats(bot).also { meta.observeChats(bot, it.chats) } }
    LaunchedEffect(refreshChats) {
        if (refreshChats) {
            model.load(afterCurrent = true)
            chatsRefreshFinished()
        }
    }
    Page(bot, back, action = { RefreshAction(model) }, container = MaterialTheme.colorScheme.surfaceContainer) { modifier ->
        Column(modifier) {
            ListRow(
                title = "新しく頼む",
                onClick = openNewChat,
                modifier = Modifier.padding(ListPadding),
                leading = { OrcaIcon(R.drawable.ic_new, tint = MaterialTheme.colorScheme.primary) },
            )
            DataContent(Modifier.weight(1f).fillMaxWidth(), model, settings) { result ->
                EntryList(empty = result.chats.isEmpty(), emptyText = "チャットはまだありません") {
                    items(meta.sorted(bot, result.chats), key = { it.id }) { chat ->
                        ChatRow(chat, bot, meta) { openChat(chat) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NewChatScreen(
    sender: SendModel<String>,
    bot: String,
    back: () -> Unit,
    settings: () -> Unit,
    checkChats: () -> Unit,
) {
    BackHandler(sender.sending) {}
    Page("新しく頼む", back = { if (!sender.sending) back() }) { modifier ->
        DetailContent(modifier.imePadding()) {
            Text(bot, style = MaterialTheme.typography.titleMedium)
            val chat = requireNotNull(sender.chat)
            val enabled = !sender.sending && sender.result == null
            AttachmentPreview(chat, enabled)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachmentPicker(chat, enabled)
                DraftField(sender, "Bot に頼みたいこと", Modifier.weight(1f), minLines = 5)
            }
            SendButton(sender, Modifier.fillMaxWidth())
            SendStatus(
                sender, settings,
                badRequest = "本文や添付の形式・サイズを確認してください",
                unknown = "送れたか確認できませんでした。チャット一覧を更新して、新しいチャットができていないか確かめてください",
            )
            if (sender.deliveryUncertain) {
                OrcaOutlinedButton(
                    onClick = checkChats,
                    enabled = !sender.sending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("チャット一覧で確かめる")
                }
            }
        }
    }
}

@Composable
internal fun ConversationScreen(
    api: OrcaApi,
    meta: ChatMetaStore,
    bot: String,
    chatId: String,
    title: String,
    back: () -> Unit,
    settings: () -> Unit,
) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val sender: SendModel<String> = viewModel {
        val state = createSavedStateHandle()
        val chat = ChatDraft(state, AttachmentLimits.Conversation)
        SendModel(state, chat, showPendingMessage = true) { text ->
            chat.sendMessage(api, bot, chatId, text) { it.open(resolver) }
            meta.markRead(bot, chatId)
            ""
        }
    }
    val model = screenModel {
        val requestedAt = System.currentTimeMillis()
        sender.loadHistory { api.messages(bot, chatId) }.also { meta.markRead(bot, chatId, requestedAt) }
    }
    val commands: ScreenModel<ChatCommands> = viewModel(key = "commands") { ScreenModel { api.commands(bot, chatId) } }
    val ready = model.data != null
    LaunchedEffect(ready) { if (ready) commands.refresh() }
    val catalog = commands.data.takeIf { commands.error == null }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pending = sender.pendingMessage
    val pendingStatus = pendingState(
        sender.sending, sender.error != null || sender.pendingAttachmentError != null,
        sender.deliveryUncertain,
    )
    LaunchedEffect(pending, sender.sending) {
        if (pending != null && sender.sending) listState.requestScrollToItem(0)
    }
    val result = sender.result
    LaunchedEffect(result) {
        if (result != null) {
            if (result.isNotEmpty()) {
                commands.refresh(afterCurrent = true)
                scope.launch { snackbar.showSnackbar(result) }
            }
            sender.resultShown()
            model.refresh(afterCurrent = true)
            listState.requestScrollToItem(0)
        }
    }
    BackHandler(sender.sending) {}
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val polling = shouldPoll(model.data?.state, model.error, model.data?.activeWorkers.orEmpty())
    LaunchedEffect(lifecycle, model, polling) {
        if (polling) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(5_000)
                if (!shouldPoll(model.data?.state, model.error, model.data?.activeWorkers.orEmpty())) break
                model.load()
            }
        }
    }
    Page(title, back = { if (!sender.sending) back() }, snackbar = snackbar, action = {
        OrcaTextButton(onClick = {
            model.refresh()
            if (ready) commands.refresh(afterCurrent = true)
        }, enabled = !model.loading) { Text("更新") }
    }) { modifier ->
        Column(modifier.imePadding()) {
            DataContent(Modifier.weight(1f).fillMaxWidth(), model, settings, keepDataOnError = true) { result ->
                if (result.entries.isEmpty() && sender.sentMessages.isEmpty() && pending == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("メッセージはまだありません")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = ListPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (pending != null) item(key = "pending-message") {
                            val reason = if (pendingStatus == PendingState.FAILED) {
                                sender.pendingAttachmentError ?: sender.error?.let(::conversationSendError)
                            } else null
                            PendingBubble(pending, pendingStatus, reason)
                        }
                        items(sender.sentMessages.asReversed()) { message ->
                            PendingBubble(message, PendingState.SENT,
                                if (model.error != null) "会話の更新に失敗" else null)
                        }
                        items(result.entries.asReversed()) { entry ->
                            when (entry) {
                                is Entry.Message -> MessageBubble(entry)
                                is Entry.Tool -> Row(
                                    Modifier.fillMaxWidth().padding(horizontal = OrcaGap),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OrcaIcon(R.drawable.ic_tool, modifier = Modifier.size(16.dp))
                                    Text(
                                        entry.name,
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(ListPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.data?.let { data ->
                    if (data.activeWorkers.isNotEmpty()) {
                        WorkingBadge("作業中 · " + data.activeWorkers.joinToString("、") { it.title })
                    } else if (data.state == "working" || data.state == "running") {
                        WorkingBadge("応答を作成中…")
                    }
                }
                if (model.data != null) {
                    ConversationInput(sender, catalog) { cmd, confirmed ->
                        sender.submit(sender.draft, confirmed) {
                            api.runCommand(bot, chatId, cmd.name, cmd.argument)
                            when (cmd.name) {
                                "clear" -> "会話をリセットしました"
                                "compact" -> "会話の圧縮をリクエストしました"
                                "model" -> "モデルを ${cmd.argument} に変更しました"
                                else -> "推論強度を ${cmd.argument} に変更しました"
                            }
                        }
                    }
                }
                SendStatus(sender, settings, conflict = conversationSendError(ApiError.CONFLICT),
                    badRequest = conversationSendError(ApiError.BAD_REQUEST),
                    showError = pending == null || pendingStatus != PendingState.FAILED)
            }
        }
    }
}

@Composable
private fun PendingBubble(message: PendingMessage, state: PendingState, reason: String?) {
    val status = when (state) {
        PendingState.SENDING -> "送信中…"
        PendingState.UNCERTAIN -> "送れたか確認できません"
        PendingState.FAILED -> "送れませんでした"
        PendingState.SENT -> "送信済み"
    }
    val body = (listOf(message.text).filter { it.isNotEmpty() } + message.attachments).joinToString("\n")
    MessageBubble(MessagePresentation(MessageKind.OWNER, "あなた", body),
        status = listOfNotNull(status, reason).joinToString("\n"))
}

@Composable
private fun MessageBubble(message: Entry.Message) {
    MessageBubble(remember(message) { presentMessage(message.role, message.text) })
}

private fun conversationSendError(error: ApiError) = when (error) {
    ApiError.CONFLICT -> "このチャットは閉じているため送れません"
    ApiError.BAD_REQUEST -> "本文・コマンドの引数・スキル・添付を確認してください"
    else -> error.text
}

@Composable
private fun MessageBubble(presentation: MessagePresentation, status: String? = null) {
    val owner = presentation.kind == MessageKind.OWNER
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (owner) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxWidth(if (owner) 0.88f else 1f),
            shape = MaterialTheme.shapes.large,
            color = when (presentation.kind) {
                MessageKind.OWNER -> colors.surfaceContainer
                MessageKind.MAIL -> colors.surface
                MessageKind.OTHER -> Color.Transparent
            },
            contentColor = colors.onSurface,
            border = if (presentation.kind != MessageKind.OTHER) BorderStroke(1.dp, colors.outlineVariant) else null,
        ) {
            Column(
                Modifier.padding(
                    horizontal = if (presentation.kind == MessageKind.OTHER) 0.dp else OrcaGap,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    presentation.heading,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                presentation.subject?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.titleSmall) } }
                if (owner) SelectionContainer { Text(presentation.body) } else MarkdownText(presentation.body)
                status?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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
    refreshItems: Boolean,
    itemsRefreshStarted: () -> Unit,
    answered: Boolean,
    answerShown: () -> Unit,
    openChat: (String, String, String) -> Unit,
    openItem: (PendingItem) -> Unit,
) {
    val model = screenModel { api.items() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(refreshItems) {
        if (refreshItems) {
            model.refresh(afterCurrent = true)
            itemsRefreshStarted()
        }
    }
    LaunchedEffect(answered) {
        if (answered) {
            snackbar.showSnackbar("答えました")
            answerShown()
        }
    }
    Page("判断待ち", back, action = { RefreshAction(model) }, snackbar = snackbar) { modifier ->
        DataContent(modifier, model, settings) { result ->
            EntryList(empty = result.items.isEmpty(), emptyText = "判断待ちはありません") {
                items(result.items, key = { "${it.bot}/${it.number}" }) { item ->
                    Column {
                        ListRow(
                            title = item.title,
                            titleMaxLines = 2,
                            subtitle = item.bot,
                            caption = relativeTime(item.lastActivityAt),
                            leading = { OrcaIcon(R.drawable.ic_pending, tint = MaterialTheme.colorScheme.tertiary) },
                            onClick = { openItem(item) },
                        )
                        item.chatId?.let { chatId ->
                            OrcaTextButton(onClick = { openChat(item.bot, chatId, item.title) }) {
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
    refreshAfterAnswer: () -> Unit,
    isCurrent: Boolean,
    answered: () -> Unit,
) {
    val model = screenModel { api.item(bot, number) }
    val sender: SendModel<Unit> = viewModel {
        SendModel(createSavedStateHandle(), onFailure = {
            if (it == ApiError.CONFLICT) {
                refreshAfterAnswer()
                model.load(afterCurrent = true)
            }
        }) { api.answer(bot, number, it) }
    }
    val result = sender.result
    LaunchedEffect(result) {
        if (result != null) refreshAfterAnswer()
    }
    LaunchedEffect(result, isCurrent) {
        if (result != null && isCurrent) answered()
    }
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    val canAnswer = model.data?.status == "needs_you" && model.error == null
    val focus = LocalFocusManager.current
    if (canAnswer) choice?.let { label ->
        if (sender.deliveryUncertain) {
            ResendDialog({ choice = null }) {
                choice = null
                sender.submit(label, resendConfirmed = true)
            }
        } else {
            AlertDialog(
                onDismissRequest = { choice = null },
                textContentColor = MaterialTheme.colorScheme.onSurface,
                text = { Text("『${label}』と答えますか？", style = MaterialTheme.typography.bodyLarge) },
                confirmButton = {
                    OrcaTextButton(onClick = {
                        choice = null
                        sender.submit(label)
                    }) { Text("はい") }
                },
                dismissButton = { OrcaTextButton(onClick = { choice = null }) { Text("キャンセル") } },
            )
        }
    }
    BackHandler(sender.sending) {}
    Page("判断待ちの詳細", back = { if (!sender.sending) back() }, action = { RefreshAction(model) }) { modifier ->
        DetailContent(modifier.imePadding()) {
            val item = model.data
            val error = model.error
            if (error != null) {
                LoadError(model, error, settings, Modifier.align(Alignment.CenterHorizontally))
            }
            if (model.loading || (item == null && error == null)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (item != null) {
                Text(bot, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                Text(item.title, style = MaterialTheme.typography.headlineSmall)
                val question = item.question
                if (question == null) {
                    Text("質問はありません")
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        MarkdownText(question.text, Modifier.padding(OrcaInset))
                    }
                    if (canAnswer) {
                        question.options.forEach { label ->
                            OrcaOutlinedButton(
                                onClick = {
                                    focus.clearFocus()
                                    choice = label
                                },
                                enabled = !sender.sending && sender.result == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(label, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
            if (canAnswer || sender.draft.isNotEmpty()) {
                DraftField(sender, "自分の言葉で答える", Modifier.fillMaxWidth(), enabled = canAnswer)
                SendButton(sender, Modifier.fillMaxWidth(), enabled = canAnswer)
            }
            SendStatus(
                sender, settings,
                conflict = "この項目はもう答え済みか、状態が変わりました",
                badRequest = "1〜8,000文字で入力してください（空白を含め、絵文字などはUTF-16コード単位で数えます）",
            )
            if (item != null) {
                item.chatId?.let { chatId ->
                    OrcaTextButton(
                        onClick = { openChat(item.bot, chatId, item.title) },
                        enabled = !sender.sending,
                    ) { Text("このチャットを開く") }
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
                    Text(summary.bot, color = orcaAccentText, style = MaterialTheme.typography.labelLarge)
                    MarkdownText(summary.content)
                    summary.chatId?.let { chatId ->
                        Button(onClick = { openChat(summary.bot, chatId, summary.bot) }, shape = MaterialTheme.shapes.medium) {
                            Text("このチャットを開く")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(OrcaInset),
        verticalArrangement = Arrangement.spacedBy(OrcaInset),
        content = content,
    )
}

@Composable
internal fun DraftField(
    sender: SendModel<*>,
    label: String,
    modifier: Modifier,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = sender.draftField,
        onValueChange = sender::edit,
        label = { Text(label) },
        minLines = minLines,
        maxLines = 5,
        readOnly = !enabled || sender.sending || sender.result != null,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedLabelColor = orcaAccentText,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
internal fun SendButton(
    sender: SendModel<*>, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    var resendText by remember { mutableStateOf<String?>(null) }
    if (enabled) resendText?.let { text ->
        ResendDialog({ resendText = null }) {
            resendText = null
            sender.submit(text, resendConfirmed = true)
        }
    }
    Button(
        shape = MaterialTheme.shapes.medium,
        onClick = {
            focus.clearFocus()
            if (onClick != null) onClick()
            else if (sender.deliveryUncertain) resendText = sender.draft else sender.submit(sender.draft)
        },
        enabled = enabled && !sender.sending && sender.result == null && sender.chat?.reading != true &&
            (sender.draft.isNotBlank() || !sender.chat?.attachments.isNullOrEmpty()),
        modifier = modifier,
    ) {
        if (sender.sending) {
            CircularProgressIndicator(
                Modifier.size(20.dp).semantics { stateDescription = "送信中" },
                strokeWidth = 2.dp,
            )
        } else {
            Text("送信")
        }
    }
}

@Composable
internal fun ResendDialog(cancel: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        text = { Text("前の送信が届いているかもしれません。もう一度送りますか？", style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { OrcaTextButton(onClick = confirm) { Text("もう一度送る") } },
        dismissButton = { OrcaTextButton(onClick = cancel) { Text("やめる") } },
    )
}

@Composable
private fun SendStatus(
    sender: SendModel<*>,
    settings: () -> Unit,
    conflict: String = ApiError.CONFLICT.text,
    badRequest: String = ApiError.BAD_REQUEST.text,
    unknown: String = ApiError.UNKNOWN_RESULT.text,
    showError: Boolean = true,
) {
    val error = sender.error
    if (error != null) {
        if (showError) Text(
            when (error) {
                ApiError.CONFLICT -> conflict
                ApiError.BAD_REQUEST -> badRequest
                else -> error.text
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
        if (error == ApiError.UNAUTHORIZED) OrcaTextButton(onClick = settings) { Text("設定を開く") }
    }
    if (sender.deliveryUncertain) {
        Text(
            unknown,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
        OrcaTextButton(onClick = sender::markAsSent, enabled = !sender.sending) {
            Text("送信済みとして閉じる")
        }
    }
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
    OrcaTextButton(onClick = { model.refresh() }, enabled = !model.loading) { Text("更新") }
}
