package dev.okumura.orcaremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel

internal fun memoBot(bots: List<Bot>, previous: String?) =
    bots.firstOrNull { it.name == previous } ?: bots.firstOrNull { it.name == "メインBot" } ?: bots.firstOrNull()

internal enum class MemoMode(val label: String, val limits: AttachmentLimits) {
    Task("タスクリスト", AttachmentLimits.NewChat),
    Inbox("Botに整理してもらう", AttachmentLimits.NewChat),
    Home("Homeチャット", AttachmentLimits.Conversation),
    New("新規チャット", AttachmentLimits.NewChat);

    fun message(text: String) = if (this == Inbox) "【秘書メモ・自動整理】\n$text" else text
}

internal sealed interface MemoResult {
    data object Task : MemoResult
    data class Chat(val bot: String, val chatId: String) : MemoResult
}

@Composable
internal fun QuickMemoScreen(
    api: OrcaApi, meta: ChatMetaStore, destination: SavedStateHandle, route: QuickMemoRoute,
    back: () -> Unit, settings: () -> Unit, openTasks: () -> Unit, open: (String, String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val prefs = context.connectionPreferences()
    val resolver = context.contentResolver
    val bots = screenModel("memo bots") { api.bots().bots }
    val selected by destination.getStateFlow<String?>("bot", null).collectAsState()
    val mode by destination.getStateFlow("memoMode", MemoMode.Task).collectAsState()
    val due by destination.getStateFlow<String?>("due", null).collectAsState()
    val taskBot by destination.getStateFlow<String?>("taskBot", null).collectAsState()
    val list = bots.data.orEmpty()
    fun target(mode: MemoMode, selectedBot: String?): String? {
        val previous = if (mode == MemoMode.Inbox) null else selectedBot ?: prefs.getString("lastMemoBot", null)
        return memoBot(bots.data.orEmpty(), previous)?.name
    }
    val bot = target(mode, selected)
    val sender: SendModel<MemoResult> = viewModel {
        val state = createSavedStateHandle()
        val chat = ChatDraft(state, mode.limits)
        SendModel(state, chat) { draft ->
            val currentMode = destination.get<MemoMode>("memoMode") ?: MemoMode.Task
            if (currentMode == MemoMode.Task) {
                if (chat.attachments.isNotEmpty()) throw AttachmentProblem("タスクリストには添付できません。添付を外すか、ほかの送り先を選んでください")
                api.createTask(memoTask(draft, destination["due"], destination["taskBot"]) ?: throw ApiFailure(ApiError.BAD_REQUEST))
                destination["due"] = null
                destination["taskBot"] = null
                SecretaryBoardWidgetProvider.requestRefresh(context)
                return@SendModel MemoResult.Task
            }
            val target = target(currentMode, destination["bot"]) ?: throw AttachmentProblem("送信先のボットがありません")
            val text = currentMode.message(draft)
            if (text.codePointCount(0, text.length) > MaxSharedText) throw ApiFailure(ApiError.BAD_REQUEST)
            val home = if (currentMode == MemoMode.New) null else api.chats(target).chats.firstOrNull { it.home }
            val chatId = when {
                home != null -> {
                    chat.sendMessage(api, target, home.id, text) { it.open(resolver) }
                    home.id
                }
                currentMode == MemoMode.Home -> throw AttachmentProblem("Homeチャットがありません。新規チャットを選んでください")
                else -> {
                    val choice = destination.modelChoice(target)
                    api.startChat(target, text, chat.inline { it.open(resolver) }, choice.model, choice.effort, choice.agent)
                }
            }
            if (currentMode != MemoMode.Inbox) prefs.edit().putString("lastMemoBot", target).apply()
            meta.markRead(target, chatId)
            SecretaryBoardWidgetProvider.requestRefresh(context)
            MemoResult.Chat(target, chatId)
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val result = sender.result
    LaunchedEffect(result) {
        when (result) {
            null -> Unit
            MemoResult.Task -> {
                sender.resultShown()
                if (snackbar.showSnackbar("タスクリストに追加しました", actionLabel = "見る", duration = SnackbarDuration.Short) ==
                    SnackbarResult.ActionPerformed) openTasks()
            }
            is MemoResult.Chat -> {
                sender.resultShown()
                open(result.bot, result.chatId)
            }
        }
    }
    val chat = requireNotNull(sender.chat)
    val enabled = !sender.sending && sender.result == null && !chat.reading
    val canSelect = enabled && !sender.deliveryUncertain
    val task = mode == MemoMode.Task
    BackHandler(sender.sending) {}
    Page("クイックメモ", back = { if (!sender.sending) back() }, snackbar = snackbar) { modifier ->
        DetailContent(modifier.imePadding()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(OrcaGap)) {
                MemoMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, enabled = canSelect, label = { Text(option.label) },
                        onClick = {
                            if (mode != option && chat.changeDestination(option.limits)) destination["memoMode"] = option
                        })
                }
            }
            if (!task) DataContent(Modifier.fillMaxWidth(), bots, settings) {
                ChoiceMenu(bot ?: "送信先のボットがありません", list.map { it.name to it.name }, bot,
                    canSelect && mode != MemoMode.Inbox && list.isNotEmpty()) { name ->
                    if (name != null && name != bot && chat.changeDestination(chat.limits)) destination["bot"] = name
                }
            }
            when (mode) {
                // Bots that fail to load leave only 担当なし, which still adds the task.
                MemoMode.Task -> TaskBotButton(taskBot, list, enabled) { destination["taskBot"] = it }
                MemoMode.Inbox -> Unit
                MemoMode.New -> if (bot != null) ModelPicker(api, bot, destination, canSelect)
                MemoMode.Home -> Unit
            }
            DraftField(sender, if (task) "タイトル（2行目以降は補足）" else "メモ", Modifier.fillMaxWidth(), minLines = 4)
            AttachmentPreview(chat, enabled)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!task) AttachmentPicker(chat, enabled)
                VoiceInput(sender, enabled, autoStart = route.voice)
                if (task) DueButton(due, enabled) { destination["due"] = it }
                Spacer(Modifier.width(8.dp))
                SendButton(sender, Modifier.weight(1f), label = if (task) "追加" else "送信",
                    enabled = task || (bot != null && list.any { it.name == bot }))
            }
            if (task) SendStatus(sender, settings, badRequest = TaskBadRequest,
                unknown = "追加できたか確認できませんでした。タスクリストを開いて確かめてください")
            else SendStatus(sender, settings,
                badRequest = if (mode == MemoMode.New && bot != null && destination.modelChoice(bot) != ModelChoice()) ModelBadRequest
                else ApiError.BAD_REQUEST.text)
            if (task) OrcaTextButton(onClick = openTasks) { Text("タスクリストを見る") }
        }
    }
}
