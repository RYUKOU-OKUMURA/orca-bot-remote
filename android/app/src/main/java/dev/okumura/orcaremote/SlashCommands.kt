package dev.okumura.orcaremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun slashQuery(text: String): String? =
    if (text.startsWith('/') && text.none { it.isWhitespace() }) text.drop(1) else null

internal fun ChatCommands.filtered(query: String) = copy(
    commands = commands.filter { it.name.startsWith(query, ignoreCase = true) },
    skills = skills.filter { it.name.startsWith(query, ignoreCase = true) },
)

internal fun typedCommand(text: String): CommandInput? {
    if (!text.startsWith('/')) return null
    val token = text.takeWhile { !it.isWhitespace() }
    val argument = text.drop(token.length).trim().ifEmpty { null }
    return when (token) {
        "/clear" -> if (argument == null) CommandInput("clear") else null
        "/compact", "/model", "/effort" -> CommandInput(token.drop(1), argument)
        else -> null
    }
}

private fun ChatCommands?.option(name: String) = if (name == "model") this?.options?.model else this?.options?.effort

internal fun OptionDescriptor?.resolveValue(argument: String): String? {
    if (this == null || choices.isEmpty()) return argument
    return (choices.find { it.value == argument } ?: choices.find { it.label.equals(argument, ignoreCase = true) })?.value
}

@Composable
private fun Suggestion(name: String, hint: String?, description: String, skill: Boolean, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (skill) Badge { Text("スキル") }
            Text("/$name", style = MaterialTheme.typography.titleSmall)
            if (!hint.isNullOrBlank()) Text(hint, style = MaterialTheme.typography.labelSmall)
        }
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ColumnScope.ConversationInput(
    sender: SendModel<String>,
    catalog: ChatCommands?,
    run: (CommandInput, Boolean) -> Unit,
) {
    val focus = LocalFocusManager.current
    val chat = requireNotNull(sender.chat)
    val enabled = !sender.sending && sender.result == null && !chat.reading
    var dialog by remember { mutableStateOf<String?>(null) }
    var resend by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun guarded(action: (Boolean) -> Unit) {
        if (!enabled) return
        if (sender.deliveryUncertain) resend = { action(true) } else action(false)
    }
    fun execute(command: CommandInput) {
        focus.clearFocus()
        sender.draft = listOfNotNull("/" + command.name, command.argument).joinToString(" ")
        guarded { confirmed -> run(command, confirmed) }
    }
    fun select(command: CommandInput, clearQuery: Boolean = false) {
        if (!sender.canStartCommand()) return
        if (clearQuery) sender.draft = ""
        when (command.name) {
            "clear" -> dialog = "clear"
            "model", "effort" -> {
                val option = catalog.option(command.name)
                val argument = command.argument
                when {
                    argument == null && option?.choices.isNullOrEmpty() -> chat.notice = "選択肢を取得できません。会話を更新してください"
                    argument == null -> dialog = command.name
                    else -> {
                        val value = option.resolveValue(argument)
                        if (value == null) chat.notice = "一覧にある値または表示名を入力してください"
                        else execute(CommandInput(command.name, value))
                    }
                }
            }
            else -> execute(command)
        }
    }

    if (dialog == "clear") AlertDialog(
        onDismissRequest = { dialog = null },
        text = { Text("この会話をリセットしますか？") },
        confirmButton = { OrcaTextButton(onClick = { dialog = null; execute(CommandInput("clear")) }) { Text("リセット") } },
        dismissButton = { OrcaTextButton(onClick = { dialog = null }) { Text("キャンセル") } },
    ) else dialog?.let { name ->
        val option = catalog.option(name)
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (name == "model") "モデルを選択" else "推論強度を選択") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(option?.choices.orEmpty()) { choice ->
                        TextButton(onClick = { dialog = null; execute(CommandInput(name, choice.value)) }) {
                            Text(if (choice.value == option?.current) "✓ ${choice.label}" else choice.label)
                        }
                    }
                }
            },
            confirmButton = { OrcaTextButton(onClick = { dialog = null }) { Text("閉じる") } },
        )
    }
    resend?.let { action -> ResendDialog({ resend = null }) { resend = null; action() } }

    AttachmentPreview(chat, enabled)
    if (chat.skills.isNotEmpty()) Row(
        Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chat.skills.forEach { skill ->
            InputChip(selected = true, enabled = enabled, onClick = { chat.removeSkill(skill) },
                label = { Text(skill.name) }, trailingIcon = { OrcaIcon(R.drawable.ic_close, "${skill.name} を外す") })
        }
    }
    val query = slashQuery(sender.draft)
    val suggestions = remember(catalog, query) { query?.let { catalog?.filtered(it) } }
    if (enabled && suggestions != null && (suggestions.commands.isNotEmpty() || suggestions.skills.isNotEmpty())) {
        Card(Modifier.weight(1f, fill = false)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(suggestions.commands) { cmd ->
                    Suggestion(cmd.name, cmd.argumentHint, cmd.description, false) {
                        when {
                            cmd.kind == "option" -> select(CommandInput(cmd.name))
                            cmd.kind == "conversation" && cmd.name == "clear" ->
                                select(CommandInput("clear"), clearQuery = true)
                            cmd.kind == "conversation" && cmd.name == "compact" && cmd.argumentHint == null ->
                                select(CommandInput("compact"))
                            else -> sender.draft = "/${cmd.name} "
                        }
                    }
                }
                items(suggestions.skills) { skill ->
                    Suggestion(skill.name, skill.argumentHint, skill.description, true) {
                        if (skill.path == null) sender.draft = "/${skill.name} " else {
                            chat.addSkill(SkillRef(skill.name, skill.path))
                            sender.draft = ""
                        }
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AttachmentPicker(chat, enabled)
        DraftField(sender, "メッセージ", Modifier.weight(1f))
        SendButton(sender, Modifier.widthIn(min = 72.dp), enabled = enabled, onClick = {
            val text = sender.draft
            val standalone = chat.skills.isEmpty() && chat.attachments.isEmpty()
            val command = if (standalone || text == sender.lastCommand) typedCommand(text) else null
            if (command == null) {
                val isTextCommand = standalone && catalog?.commands.orEmpty().any {
                    it.kind == "text" && "/" + it.name == text.trimEnd()
                }
                guarded { sender.submit(text, it, showPending = !isTextCommand) }
            } else select(command)
        })
    }
}
