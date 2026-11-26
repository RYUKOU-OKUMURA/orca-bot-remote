package dev.okumura.orcaremote

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    // Keeps an assignment to a bot that is gone or not loaded, so it can be seen and cleared.
    val names = (bots.map { it.name } + listOfNotNull(bot)).distinct()
    ChoiceMenu(bot ?: "担当なし", listOf(null to "担当なし") + names.map { it to it }, bot, enabled, change)
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

internal enum class DueUrgency { OVERDUE, TODAY, TOMORROW, UPCOMING }

internal fun dueUrgency(due: String?, today: LocalDate): DueUrgency? {
    val date = parseDue(due) ?: return null
    return when {
        date < today -> DueUrgency.OVERDUE
        date == today -> DueUrgency.TODAY
        date == today.plusDays(1) -> DueUrgency.TOMORROW
        else -> DueUrgency.UPCOMING
    }
}

/** A second tap on the chosen day clears it. */
internal fun toggleDue(current: String?, target: String): String? = if (current == target) null else target

/** 今日 (0) or 明日 (1) counted from [today], which the caller reads at tap time. */
internal fun quickDue(current: String?, days: Long, today: LocalDate): String? = toggleDue(current, today.plusDays(days).toString())

internal fun millisUntilNextDay(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis().coerceAtLeast(1L)

/** Today's date that moves on at local midnight and when the screen comes back. */
@Composable
internal fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        resumeTick++
        onPauseOrDispose {}
    }
    // Restarted on resume so a time zone change re-times the wait.
    LaunchedEffect(resumeTick) {
        while (true) {
            delay(millisUntilNextDay(ZonedDateTime.now()))
            today = LocalDate.now()
        }
    }
    return today
}

/** Changes in flight by task id; kept across rotation so a running change still blocks the same task. */
internal class TaskMutations(private val scope: CoroutineScope? = null) : ViewModel() {
    var busy by mutableStateOf(emptySet<String>())
        private set
    var message by mutableStateOf<String?>(null)

    /** Starts [action] unless [id] already has a change in flight; false means nothing was started. */
    fun run(id: String, action: suspend () -> Unit): Boolean {
        if (id in busy) return false
        busy = busy + id
        (scope ?: viewModelScope).launch {
            try {
                action()
            } finally {
                busy = busy - id
            }
        }
        return true
    }
}

internal enum class TaskSortOrder(val label: String) { DUE("期限順"), CREATED("新しい順") }

private const val TaskSortOrderKey = "taskSortOrder"

internal fun taskSortOrder(name: String?) = TaskSortOrder.entries.firstOrNull { it.name == name } ?: TaskSortOrder.DUE

internal fun SharedPreferences.taskSortOrder() = taskSortOrder(getString(TaskSortOrderKey, null))

internal fun SharedPreferences.saveTaskSortOrder(order: TaskSortOrder) = edit().putString(TaskSortOrderKey, order.name).apply()

/** [TaskSortOrder.DUE] is the server's order: open tasks by due date then newest, done tasks by completion. */
internal fun sortTasks(tasks: List<MemoTask>, order: TaskSortOrder = TaskSortOrder.DUE): List<MemoTask> {
    val (open, done) = tasks.partition { !it.done }
    val sortedOpen = if (order == TaskSortOrder.CREATED) open.sortedByDescending { it.createdAt } else {
        val (dated, undated) = open.partition { parseDue(it.due) != null }
        dated.sortedWith(compareBy<MemoTask> { it.due }.thenByDescending { it.createdAt }) + undated.sortedByDescending { it.createdAt }
    }
    return sortedOpen + done.sortedByDescending { it.doneAt ?: it.updatedAt }
}

internal fun replaceTask(tasks: List<MemoTask>, task: MemoTask, order: TaskSortOrder = TaskSortOrder.DUE) =
    sortTasks(tasks.filter { it.id != task.id } + task, order)

private fun taskError(error: ApiError) = when (error) {
    ApiError.NOT_FOUND -> "タスクが見つかりません。ほかで削除された可能性があります"
    ApiError.BAD_REQUEST -> TaskBadRequest
    ApiError.UNKNOWN_RESULT -> TaskUnknown
    else -> error.text
}

internal const val TaskBadRequest = "タイトルは1〜200文字、補足メモは2000文字までです"
internal const val TaskUnknown = "反映されたか確認できませんでした。タスクリストを更新して確かめてください"

private fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Picks a day; キャンセル keeps the current due date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DueDatePickerDialog(initialDue: String?, today: LocalDate, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val initial = parseDue(initialDue) ?: today
    val state = rememberDatePickerState(initial.millis(), yearRange = minOf(1900, initial.year)..maxOf(2100, initial.year))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            OrcaTextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                onDismiss()
            }) { Text("決定") }
        },
        dismissButton = { OrcaTextButton(onClick = onDismiss) { Text("キャンセル") } },
    ) { DatePicker(state) }
}

/** Pill in the shape of [ValueChip]; a chosen value gets an accent border and text. */
@Composable
private fun DuePill(label: String, enabled: Boolean, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tone = if (selected) accent else colors.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) tone else tone.copy(alpha = 0.38f),
        border = when {
            selected -> BorderStroke(1.5.dp, if (enabled) accent else accent.copy(alpha = 0.38f))
            else -> BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant)
        },
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 今日・明日 toggle in one tap, the calendar chip picks any day. Emits its chips into the caller's FlowRow. */
@Composable
internal fun DueChips(due: String?, today: LocalDate, enabled: Boolean, change: (String?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val accent = orcaAccentText
    val quick = listOf("今日" to 0L, "明日" to 1L)
    quick.forEach { (label, days) ->
        val chosen = due == today.plusDays(days).toString()
        DuePill(label, enabled, selected = chosen, accent, Modifier.semantics { selected = chosen }) {
            change(quickDue(due, days, LocalDate.now()))
        }
    }
    val other = due?.takeIf { day -> quick.none { today.plusDays(it.second).toString() == day } }?.let { dueLabel(it, today) }
    DuePill((other?.first ?: "日付") + " ▾", enabled, selected = other != null,
        if (dueUrgency(due, today) == DueUrgency.OVERDUE) MaterialTheme.colorScheme.error else accent) { picking = true }
    if (due != null) DuePill("×", enabled, selected = false, accent, Modifier.semantics { contentDescription = "期限をクリア" }) { change(null) }
    if (picking) DueDatePickerDialog(due, today, onDismiss = { picking = false }) { change(it) }
}

@Composable
private fun dueColor(urgency: DueUrgency?): Color = when (urgency) {
    null -> MaterialTheme.colorScheme.outline
    DueUrgency.OVERDUE -> MaterialTheme.colorScheme.error
    DueUrgency.TODAY -> MaterialTheme.colorScheme.tertiary
    DueUrgency.TOMORROW -> orcaAccentText
    DueUrgency.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DueMenuItem(text: String, current: Boolean, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(text, fontWeight = if (current) FontWeight.Bold else null) },
    trailingIcon = if (current) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
    onClick = onClick,
)

/** Changes only the due date of a listed task; picking the current value sends nothing. */
@Composable
private fun DueQuickMenu(task: MemoTask, today: LocalDate, enabled: Boolean, change: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var picking by rememberSaveable(task.id) { mutableStateOf(false) }
    fun pick(due: String?) {
        open = false
        if (due != task.due) change(due)
    }
    val days = listOf("今日" to 0L, "明日" to 1L)
    Box {
        val tint = dueColor(dueUrgency(task.due, today))
        IconButton(onClick = { open = true }, enabled = enabled) {
            OrcaIcon(R.drawable.ic_calendar, "${task.title}の期限を変更", tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            days.forEach { (label, offset) ->
                DueMenuItem(label, task.due == today.plusDays(offset).toString()) { pick(LocalDate.now().plusDays(offset).toString()) }
            }
            DueMenuItem("カレンダーで選択…", task.due != null && days.none { today.plusDays(it.second).toString() == task.due }) {
                open = false
                picking = true
            }
            if (task.due != null) DueMenuItem("期限をクリア", false) { pick(null) }
        }
    }
    if (picking) DueDatePickerDialog(task.due, today, onDismiss = { picking = false }) { pick(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksScreen(api: OrcaApi, back: () -> Unit, settings: () -> Unit, ask: (bot: String, text: String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val prefs = context.connectionPreferences()
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
    val mutations: TaskMutations = viewModel(key = "task mutations")
    val busy = mutations.busy
    val today = rememberToday()
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(prefs.taskSortOrder()) }
    val due by adder.savedState.getStateFlow<String?>("due", null).collectAsState()
    val taskBot by adder.savedState.getStateFlow<String?>("bot", null).collectAsState()
    val added = adder.result
    LaunchedEffect(added) {
        if (added != null) {
            adder.resultShown()
            model.update { replaceTask(it, added, sortOrder) }
        }
    }
    LaunchedEffect(mutations.message) {
        mutations.message?.let {
            snackbar.showSnackbar(it)
            mutations.message = null
        }
    }
    // A null result means the task was deleted. False when the task already has a change in flight.
    fun change(id: String, action: suspend () -> MemoTask?) = mutations.run(id) {
        try {
            val task = action()
            model.update { tasks -> if (task == null) tasks.filter { it.id != id } else replaceTask(tasks, task, sortOrder) }
            SecretaryBoardWidgetProvider.requestRefresh(context)
        } catch (failure: ApiFailure) {
            mutations.message = taskError(failure.error)
            if (failure.error == ApiError.NOT_FOUND || failure.error == ApiError.UNKNOWN_RESULT) model.refresh(afterCurrent = true)
        }
    }
    val toggle = { task: MemoTask -> change(task.id) { api.setTaskDone(task.id, !task.done) } }
    val setDue = { task: MemoTask, due: String? -> change(task.id) { api.setTaskDue(task.id, due) } }

    Page("タスクリスト", back, action = { RefreshAction(model) }, snackbar = snackbar) { modifier ->
        Column(modifier.imePadding()) {
            Column(Modifier.padding(horizontal = OrcaInset, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = !adder.sending && adder.result == null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceInput(adder, enabled)
                    DraftField(adder, "タスクを追加（2行目以降は補足）", Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        DueChips(due, today, enabled) { adder.savedState["due"] = it }
                        TaskBotButton(taskBot, bots, enabled) { adder.savedState["bot"] = it }
                    }
                    SendButton(adder, label = "追加")
                }
                SendStatus(adder, settings, badRequest = TaskBadRequest,
                    unknown = "追加できたか確認できませんでした。一覧を更新して確かめてください")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DataContent(Modifier.weight(1f).fillMaxWidth(), model, settings, keepDataOnError = true) { tasks ->
                val (open, done) = sortTasks(tasks, sortOrder).partition { !it.done }
                PullToRefreshBox(isRefreshing = model.loading, onRefresh = { model.refresh() }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        if (open.isEmpty()) item(key = "header:empty") { Text("未完了のタスクはありません", Modifier.padding(OrcaInset)) }
                        if (open.size >= 2) item(key = "header:sort") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                ChoiceMenu(sortOrder.label, TaskSortOrder.entries.map { it.name to it.label }, sortOrder.name, enabled = true) {
                                    sortOrder = taskSortOrder(it)
                                    prefs.saveTaskSortOrder(sortOrder)
                                }
                            }
                        }
                        items(open, key = { "task:${it.id}" }) { task ->
                            TaskItem(task, bots, today, task.id in busy, { toggle(task) }, { setDue(task, it) }) { editing = task.id }
                        }
                        if (done.isNotEmpty()) item(key = "header:done") {
                            Text(
                                (if (showDone) "▾ " else "▸ ") + "完了済み (${done.size}件)",
                                Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(OrcaInset).semantics { heading() },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showDone) items(done, key = { "task:${it.id}" }) { task ->
                            TaskItem(task, bots, today, task.id in busy, { toggle(task) }, {}) { editing = task.id }
                        }
                    }
                }
            }
        }
    }

    model.data?.firstOrNull { it.id == editing }?.let { task ->
        TaskEditDialog(task, bots, today, task.id in busy, close = { editing = null }, ask = ask,
            save = { edit -> change(task.id) { api.editTask(task.id, edit) } },
            delete = { change(task.id) { api.deleteTask(task.id).let { null } } })
    }
}

@Composable
private fun TaskItem(
    task: MemoTask, bots: List<Bot>, today: LocalDate, busy: Boolean, toggle: () -> Unit, setDue: (String?) -> Unit, edit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = edit).padding(vertical = 4.dp),
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
                    Text(name + if (due != null) " · " else "", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                due?.let { (label, _) ->
                    val urgency = dueUrgency(task.due, today).takeUnless { task.done }
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = if (urgency == null) MaterialTheme.colorScheme.onSurfaceVariant else dueColor(urgency),
                        fontWeight = if (urgency == DueUrgency.OVERDUE || urgency == DueUrgency.TODAY) FontWeight.SemiBold else null,
                        maxLines = 1, softWrap = false)
                }
            }
        }
        if (!task.done) DueQuickMenu(task, today, enabled = !busy, setDue)
    }
}

@Composable
private fun TaskEditDialog(
    task: MemoTask, bots: List<Bot>, today: LocalDate, busy: Boolean, close: () -> Unit, ask: (String, String) -> Unit,
    save: (TaskEdit) -> Boolean, delete: () -> Boolean,
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("タイトル") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("補足メモ") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    DueChips(due, today, enabled = true) { due = it }
                    TaskBotButton(bot, bots, enabled = true) { bot = it }
                }
                taskAskTarget(bot, bots)?.let { target ->
                    // Opens the new-chat form with the task as a draft. Nothing is sent until the owner sends it.
                    OrcaOutlinedButton({
                        close()
                        ask(target, taskRequest(title, note))
                    }, enabled = title.isNotBlank()) { Text("${target}に頼む") }
                }
                OrcaTextButton(enabled = !busy, onClick = {
                    if (!confirmDelete) confirmDelete = true
                    else if (delete()) close()
                }) { Text(if (confirmDelete) "もう一度押すと削除します" else "削除", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            OrcaTextButton(enabled = !busy && taskInputValid(title, note), onClick = {
                if (save(TaskEdit(title.trim(), note.trim().ifEmpty { null }, due, bot))) close()
            }) { Text("保存") }
        },
        dismissButton = { OrcaTextButton(onClick = close) { Text("やめる") } },
    )
}
