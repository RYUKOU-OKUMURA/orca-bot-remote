package dev.okumura.orcaremote

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle

internal const val SharedDraftKey = "sharedDraft"

@Composable
internal fun ApplySharedDraft(destination: SavedStateHandle, sender: SendModel<String>) {
    val resolver = LocalContext.current.contentResolver
    LaunchedEffect(destination, sender) {
        val encoded = destination.get<String>(SharedDraftKey) ?: return@LaunchedEffect
        val shared = apiJson.decodeFromString<SharedDraft>(encoded)
        sender.chat?.add(resolver, shared.uris.map(Uri::parse))
        if (shared.text.isNotBlank()) sender.draft = listOf(sender.draft, shared.text).filter(String::isNotBlank).joinToString("\n")
        if (shared.textTruncated) sender.chat?.let { chat ->
            chat.notice = listOfNotNull(chat.notice, "共有した文章は先頭の10,000文字まで取り込みました。送信前に内容を確認してください").joinToString("\n")
        }
        destination.remove<String>(SharedDraftKey)
    }
}

@Composable
internal fun ShareScreen(api: OrcaApi, back: () -> Unit, settings: () -> Unit, select: (String, Chat?) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val bot = selected
    androidx.activity.compose.BackHandler(bot != null) { selected = null }
    Page(if (bot == null) "共有先のボットを選ぶ" else "$bot に共有", back = { if (bot == null) back() else selected = null }) { modifier ->
        if (bot == null) {
            val bots = screenModel("share bots") { api.bots().bots }
            DataContent(modifier, bots, settings) { list ->
                LazyColumn {
                    if (list.isEmpty()) item { Text("Bot が見つかりません", Modifier.padding(OrcaInset)) }
                    items(list, key = { it.id }) {
                        ListRow(it.name, { selected = it.name }, leading = { OrcaIcon(R.drawable.ic_chat) })
                    }
                }
            }
        } else {
            val chats = screenModel("share chats/$bot") { api.chats(bot).chats }
            androidx.compose.foundation.layout.Column(modifier) {
                ListRow("新しいチャットで送る", { select(bot, null) }, leading = { OrcaIcon(R.drawable.ic_new) })
                DataContent(Modifier.weight(1f), chats, settings) { list ->
                    LazyColumn {
                        items(list, key = { it.id }) { chat ->
                            ListRow(chat.displayTitle, { select(bot, chat) }, leading = { OrcaIcon(R.drawable.ic_chat) })
                        }
                    }
                }
            }
        }
    }
}
