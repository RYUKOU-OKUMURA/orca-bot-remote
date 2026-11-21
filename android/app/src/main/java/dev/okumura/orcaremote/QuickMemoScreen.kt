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

@Composable
internal fun QuickMemoScreen(
    api: OrcaApi, meta: ChatMetaStore, destination: SavedStateHandle, voice: Boolean,
    back: () -> Unit, settings: () -> Unit, open: (String, String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val prefs = context.connectionPreferences()
    val resolver = context.contentResolver
    val bots = screenModel("memo bots") { api.bots().bots }
    val bot by destination.getStateFlow<String?>("bot", null).collectAsState()
    val newChat by destination.getStateFlow("newChat", false).collectAsState()
    val sender: SendModel<String> = viewModel {
        val state = createSavedStateHandle()
        val chat = ChatDraft(state, if (newChat) AttachmentLimits.NewChat else AttachmentLimits.Conversation)
        SendModel(state, chat) { text ->
            val target = requireNotNull(destination.get<String>("bot"))
            val chatId = if (destination.get<Boolean>("newChat") == true) {
                api.startChat(target, text, chat.inline { it.open(resolver) })
            } else {
                val home = api.chats(target).chats.firstOrNull { it.home }
                    ?: throw AttachmentProblem("Homeチャットがありません。新規チャットを選んでください")
                chat.sendMessage(api, target, home.id, text) { it.open(resolver) }
                home.id
            }
            prefs.edit().putString("lastMemoBot", target).apply()
            meta.markRead(target, chatId)
            chatId
        }
    }
    val list = bots.data.orEmpty()
    LaunchedEffect(bots.data) {
        if (bots.data != null && list.none { it.name == bot }) {
            destination["bot"] = memoBot(list, prefs.getString("lastMemoBot", null))?.name
        }
    }
    val result = sender.result
    LaunchedEffect(result) {
        if (result != null && bot != null) {
            sender.resultShown()
            open(requireNotNull(bot), result)
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
                    OrcaOutlinedButton({ menu = true }, enabled = canSelect && list.isNotEmpty()) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(OrcaGap)) {
                listOf(false to "Homeチャット", true to "新規チャット").forEach { (mode, label) ->
                    FilterChip(selected = newChat == mode, enabled = canSelect, label = { Text(label) },
                        onClick = {
                            val limits = if (mode) AttachmentLimits.NewChat else AttachmentLimits.Conversation
                            if (newChat != mode && chat.changeDestination(limits)) destination["newChat"] = mode
                        })
                }
            }
            DraftField(sender, "メモ", Modifier.fillMaxWidth(), minLines = 4)
            AttachmentPreview(chat, enabled)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttachmentPicker(chat, enabled)
                VoiceInput(sender, enabled, autoStart = voice)
                SendButton(sender, Modifier.weight(1f), enabled = bot != null && list.any { it.name == bot })
            }
            SendStatus(sender, settings)
        }
    }
}
