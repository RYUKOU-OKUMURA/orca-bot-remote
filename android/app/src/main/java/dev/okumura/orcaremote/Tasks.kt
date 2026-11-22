package dev.okumura.orcaremote

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

internal const val MaxTaskTitle = 200
internal const val MaxTaskNote = 2000

private fun String.codePointLength() = codePointCount(0, length)
private fun String.takeCodePoints(count: Int) = if (codePointLength() <= count) this else substring(0, offsetByCodePoints(0, count))

/** First non-blank line is the title; the rest is the note. An over-long first line is kept whole in the note. */
internal fun memoTask(text: String, due: String? = null, bot: String? = null): NewTask? {
    val lines = text.lines()
    val index = lines.indexOfFirst { it.isNotBlank() }
    if (index < 0) return null
    val first = lines[index].trim()
    val title = first.takeCodePoints(MaxTaskTitle)
    val rest = lines.drop(index + 1).joinToString("\n").trim()
    val note = listOfNotNull(first.takeIf { it != title }, rest.ifEmpty { null }).joinToString("\n")
    return NewTask(title, note.ifEmpty { null }, due, bot)
}

/** Draft for a new chat that asks the bot to do the task. */
internal fun taskRequest(title: String, note: String?) = listOfNotNull(title.trim(), note?.trim()?.ifEmpty { null }).joinToString("\n")

/** The assigned bot, or the main bot when nobody is assigned. */
internal fun taskAskTarget(assigned: String?, bots: List<Bot>) = assigned ?: memoBot(bots, null)?.name

@Composable
internal fun TaskBotButton(bot: String?, bots: List<Bot>, enabled: Boolean, change: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OrcaOutlinedButton({ open = true }, enabled = enabled) { Text("担当: " + (bot ?: "未指定")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Keeps an assignment to a bot that is gone or not loaded, so it can be seen and cleared.
            (listOf(null) + (bots.map { it.name } + listOfNotNull(bot)).distinct()).forEach { name ->
                DropdownMenuItem(text = { Text(name ?: "未指定") }, onClick = {
                    open = false
                    change(name)
                })
            }
        }
    }
}

internal fun taskInputValid(title: String, note: String) =
    title.isNotBlank() && title.trim().codePointLength() <= MaxTaskTitle && note.trim().codePointLength() <= MaxTaskNote

private val dueFormat = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun parseDue(value: String?): LocalDate? =
    value?.takeIf { dueFormat.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Label and whether the due date is today or already past. */
internal fun dueLabel(due: String?, today: LocalDate): Pair<String, Boolean>? {
    val date = parseDue(due) ?: return null
    val label = when {
        date < today -> "期限切れ ${date.monthValue}/${date.dayOfMonth}"
        date == today -> "今日まで"
        date == today.plusDays(1) -> "明日まで"
        date.year == today.year -> "${date.monthValue}/${date.dayOfMonth}まで"
        else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}まで"
    }
    return label to (date <= today)
}

/** Same order as the server: open tasks by due date then newest, done tasks by completion. */
internal fun sortTasks(tasks: List<MemoTask>): List<MemoTask> {
    val (open, done) = tasks.partition { !it.done }
    val (dated, undated) = open.partition { parseDue(it.due) != null }
    return dated.sortedBy { it.due } + undated.sortedByDescending { it.createdAt } +
        done.sortedByDescending { it.doneAt ?: it.updatedAt }
}

internal fun replaceTask(tasks: List<MemoTask>, task: MemoTask) = sortTasks(tasks.filter { it.id != task.id } + task)

private fun taskError(error: ApiError) = when (error) {
    ApiError.NOT_FOUND -> "タスクが見つかりません。ほかで削除された可能性があります"
    ApiError.BAD_REQUEST -> TaskBadRequest
    ApiError.UNKNOWN_RESULT -> TaskUnknown
    else -> error.text
}

internal const val TaskBadRequest = "タイトルは1〜200文字、補足メモは2000文字までです"
internal const val TaskUnknown = "反映されたか確認できませんでした。タスクリストを更新して確かめてください"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DueButton(due: String?, enabled: Boolean, change: (String?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()
    OrcaOutlinedButton({ picking = true }, enabled = enabled) {
        Text(dueLabel(due, today)?.first ?: "期限なし")
    }
    if (picking) {
        val state = rememberDatePickerState(parseDue(due)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                OrcaTextButton(onClick = {
                    picking = false
                    change(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() })
                }) { Text("決定") }
            },
            dismissButton = {
                OrcaTextButton(onClick = {
                    picking = false
                    change(null)
                }) { Text("期限なし") }
            },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksScreen(api: OrcaApi, back: () -> Unit, settings: () -> Unit, ask: (bot: String, text: String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val model = screenModel("tasks", refreshOnEnter = true) { api.tasks().tasks }
    // Optional: without bots the list still works, only icons and assignment choices are missing.
    val bots = screenModel("task bots") { api.bots().bots }.data.orEmpty()
    val adder: SendModel<MemoTask> = viewModel(key = "task add") {
        val state = createSavedStateHandle()
        SendModel(state) { text ->
            val task = memoTask(text, state["due"], state["bot"]) ?: throw ApiFailure(ApiError.BAD_REQUEST)
            api.createTask(task).also {
                state["due"] = null
                state["bot"] = null
                SecretaryBoardWidgetProvider.requestRefresh(context)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val due by adder.savedState.getStateFlow<String?>("due", null).collectAsState()
    val taskBot by adder.savedState.getStateFlow<String?>("bot", null).collectAsState()
    val added = adder.result
    LaunchedEffect(added) {
        if (added != null) {
            adder.resultShown()
            model.update { replaceTask(it, added) }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }
    // A null result means the task was deleted.
    fun change(id: String, action: suspend () -> MemoTask?) {
        if (id in busy) return
        busy = busy + id
        model.viewModelScope.launch {
            try {
                val task = action()
                model.update { tasks -> if (task == null) tasks.filter { it.id != id } else replaceTask(tasks, task) }
                SecretaryBoardWidgetProvider.requestRefresh(context)
            } catch (failure: ApiFailure) {
                message = taskError(failure.error)
                if (failure.error == ApiError.NOT_FOUND || failure.error == ApiError.UNKNOWN_RESULT) model.refresh(afterCurrent = true)
            } finally {
                busy = busy - id
            }
        }
    }
    val toggle = { task: MemoTask -> change(task.id) { api.setTaskDone(task.id, !task.done) } }

    Page("タスクリスト", back, action = { RefreshAction(model) }, snackbar = snackbar) { modifier ->
        Column(modifier.imePadding()) {
            Column(Modifier.padding(horizontal = OrcaInset, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = !adder.sending && adder.result == null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceInput(adder, enabled)
                    DraftField(adder, "タスクを追加（2行目以降は補足）", Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DueButton(due, enabled) { adder.savedState["due"] = it }
                        TaskBotButton(taskBot, bots, enabled) { adder.savedState["bot"] = it }
                    }
                    SendButton(adder, label = "追加")
                }
                SendStatus(adder, settings, badRequest = TaskBadRequest,
                    unknown = "追加できたか確認できませんでした。一覧を更新して確かめてください")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DataContent(Modifier.weight(1f).fillMaxWidth(), model, settings, keepDataOnError = true) { tasks ->
                val today = LocalDate.now()
                val (open, done) = tasks.partition { !it.done }
                PullToRefreshBox(isRefreshing = model.loading, onRefresh = { model.refresh() }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        if (open.isEmpty()) item { Text("未完了のタスクはありません", Modifier.padding(OrcaInset)) }
                        items(open, key = { it.id }) { task ->
                            TaskItem(task, bots, today, task.id in busy, { toggle(task) }) { editing = task.id }
                        }
                        if (done.isNotEmpty()) item(key = "done header") {
                            Text(
                                (if (showDone) "▾ " else "▸ ") + "完了済み (${done.size}件)",
                                Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(OrcaInset).semantics { heading() },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showDone) items(done, key = { it.id }) { task ->
                            TaskItem(task, bots, today, task.id in busy, { toggle(task) }) { editing = task.id }
                        }
                    }
                }
            }
        }
    }

    model.data?.firstOrNull { it.id == editing }?.let { task ->
        TaskEditDialog(task, bots, close = { editing = null }, ask = ask,
            save = { edit -> change(task.id) { api.editTask(task.id, edit) } },
            delete = { change(task.id) { api.deleteTask(task.id).let { null } } })
    }
}

@Composable
private fun TaskItem(task: MemoTask, bots: List<Bot>, today: LocalDate, busy: Boolean, toggle: () -> Unit, edit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = edit).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.done, onCheckedChange = { toggle() }, enabled = !busy)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            task.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val due = dueLabel(task.due, today)
            if (task.bot != null || due != null) Row(verticalAlignment = Alignment.CenterVertically) {
                task.bot?.let { name ->
                    avatarDrawable(bots.firstOrNull { it.name == name }?.avatar)?.let {
                        Image(painterResource(it), contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp).size(18.dp).clip(MaterialTheme.shapes.extraSmall))
                    }
                    Text(name + if (due != null) " · " else "", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                due?.let { (label, urgent) ->
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (urgent && !task.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TaskEditDialog(
    task: MemoTask, bots: List<Bot>, close: () -> Unit, ask: (String, String) -> Unit,
    save: (TaskEdit) -> Unit, delete: () -> Unit,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var note by rememberSaveable(task.id) { mutableStateOf(task.note.orEmpty()) }
    var due by rememberSaveable(task.id) { mutableStateOf(task.due) }
    var bot by rememberSaveable(task.id) { mutableStateOf(task.bot) }
    var confirmDelete by rememberSaveable(task.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("タスクを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("タイトル") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("補足メモ") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DueButton(due, enabled = true) { due = it }
                    TaskBotButton(bot, bots, enabled = true) { bot = it }
                }
                taskAskTarget(bot, bots)?.let { target ->
                    // Opens the new-chat form with the task as a draft. Nothing is sent until the owner sends it.
                    OrcaOutlinedButton({
                        close()
                        ask(target, taskRequest(title, note))
                    }, enabled = title.isNotBlank()) { Text("${target}に頼む") }
                }
                OrcaTextButton(onClick = {
                    if (confirmDelete) {
                        close()
                        delete()
                    } else confirmDelete = true
                }) { Text(if (confirmDelete) "もう一度押すと削除します" else "削除", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            OrcaTextButton(enabled = taskInputValid(title, note), onClick = {
                close()
                save(TaskEdit(title.trim(), note.trim().ifEmpty { null }, due, bot))
            }) { Text("保存") }
        },
        dismissButton = { OrcaTextButton(onClick = close) { Text("やめる") } },
    )
}
