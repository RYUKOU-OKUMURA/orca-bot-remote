package dev.okumura.orcaremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel

internal fun memoBot(bots: List<Bot>, previous: String?) =
    bots.firstOrNull { it.name == previous } ?: bots.firstOrNull { it.name == "メインBot" } ?: bots.firstOrNull()

internal enum class MemoMode(val label: String, val limits: AttachmentLimits) {
    Inbox("秘書メモ（自動整理）", AttachmentLimits.NewChat),
    Home("Homeチャット", AttachmentLimits.Conversation),
    New("新規チャット", AttachmentLimits.NewChat);

    fun message(text: String) = if (this == Inbox) "【秘書メモ・自動整理】\n$text" else text
}

@Composable
internal fun QuickMemoScreen(
    api: OrcaApi, meta: ChatMetaStore, destination: SavedStateHandle, route: QuickMemoRoute,
    back: () -> Unit, settings: () -> Unit, open: (String, String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val prefs = context.connectionPreferences()
    val resolver = context.contentResolver
    val bots = screenModel("memo bots") { api.bots().bots }
    val selected by destination.getStateFlow<String?>("bot", null).collectAsState()
    val initialMode = when {
        route.inbox -> MemoMode.Inbox
        destination.get<Boolean>("newChat") == true -> MemoMode.New
        else -> MemoMode.Home
    }
    val mode by destination.getStateFlow("memoMode", initialMode).collectAsState()
    val list = bots.data.orEmpty()
    fun target(mode: MemoMode, selectedBot: String?): String? {
        val previous = if (mode == MemoMode.Inbox) null else selectedBot ?: prefs.getString("lastMemoBot", null)
        return memoBot(bots.data.orEmpty(), previous)?.name
    }
    val bot = target(mode, selected)
    val sender: SendModel<Pair<String, String>> = viewModel {
        val state = createSavedStateHandle()
        val chat = ChatDraft(state, mode.limits)
        SendModel(state, chat) { draft ->
            val currentMode = destination.get<MemoMode>("memoMode") ?: initialMode
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
                else -> api.startChat(target, text, chat.inline { it.open(resolver) })
            }
            if (currentMode != MemoMode.Inbox) prefs.edit().putString("lastMemoBot", target).apply()
            meta.markRead(target, chatId)
            target to chatId
        }
    }

    val result = sender.result
    LaunchedEffect(result) {
        if (result != null) {
            sender.resultShown()
            open(result.first, result.second)
        }
    }
    val chat = requireNotNull(sender.chat)
    val enabled = !sender.sending && sender.result == null && !chat.reading
    val canSelect = enabled && !sender.deliveryUncertain
    BackHandler(sender.sending) {}
    Page("クイックメモ", back = { if (!sender.sending) back() }) { modifier ->
        DetailContent(modifier.imePadding()) {
            DataContent(Modifier.fillMaxWidth(), bots, settings) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    OrcaOutlinedButton({ menu = true }, enabled = canSelect && mode != MemoMode.Inbox && list.isNotEmpty()) {
                        Text(bot ?: "送信先のボットがありません")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        list.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = {
                                menu = false
                                if (item.name != bot && chat.changeDestination(chat.limits)) destination["bot"] = item.name
                            })
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(OrcaGap)) {
                MemoMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, enabled = canSelect, label = { Text(option.label) },
                        onClick = {
                            if (mode != option && chat.changeDestination(option.limits)) destination["memoMode"] = option
                        })
                }
            }
            if (mode == MemoMode.Inbox) {
                Text("雑多なメモを投げるとメインBotがタスクへ自動整理し、ボード＆リマインドに反映します")
            }
            DraftField(sender, "メモ", Modifier.fillMaxWidth(), minLines = 4)
            AttachmentPreview(chat, enabled)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachmentPicker(chat, enabled)
                VoiceInput(sender, enabled, autoStart = route.voice)
                SendButton(sender, Modifier.weight(1f), enabled = bot != null && list.any { it.name == bot })
            }
            SendStatus(sender, settings)
        }
    }
}
