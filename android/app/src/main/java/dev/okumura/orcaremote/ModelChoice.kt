package dev.okumura.orcaremote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle

/** null means "keep the bot's default", which is not sent to the server. */
internal data class ModelChoice(val model: String? = null, val effort: String? = null, val agent: String? = null)

internal fun BotOptions.modelOption(model: String?): BotModelOption? =
    if (model != null) models.firstOrNull { it.id == model }
    else models.firstOrNull { it.id == defaultModel } ?: models.firstOrNull { it.isDefault }

internal fun BotOptions.efforts(model: String?): List<OptionChoice> = modelOption(model)?.efforts.orEmpty()

/** Picks a model and drops an effort the new model does not offer. The default model is sent as null. */
internal fun BotOptions.choose(model: String?, effort: String?): ModelChoice {
    val picked = model?.takeIf { it != modelOption(null)?.id }
    return ModelChoice(picked, effort?.takeIf { value -> efforts(picked).any { it.value == value } })
}

/** The default effort of the default model is sent as null. */
internal fun BotOptions.chooseEffort(choice: ModelChoice, effort: String?) =
    choice.copy(effort = effort?.takeIf { choice.model != null || it != defaultEffort })

/** The CLI name the owner knows, falling back to the server's label. */
internal fun BotAgentOption.displayName() = when {
    id == "claude" || label == "Claude" -> "Claude Code"
    id == "codex" -> "Codex"
    id == "antigravity" -> "Antigravity"
    id == "opencode" -> "OpenCode"
    id == "hermes" -> "Hermes"
    else -> label
}

internal val BotOptions.defaultAgentOption: BotAgentOption?
    get() = agents.firstOrNull { it.isDefault } ?: agents.firstOrNull { it.id == defaultAgent }

/** A new agent has its own models, so the model and effort go back to the defaults. The bot's own agent is sent as null. */
internal fun BotOptions.chooseAgent(agent: String?) =
    ModelChoice(agent = agent?.takeIf { it != defaultAgentOption?.id && it != defaultAgent })

// The choice belongs to one bot. Another bot starts from its defaults.
internal fun SavedStateHandle.modelChoice(bot: String): ModelChoice =
    if (get<String>("modelBot") == bot) ModelChoice(get("model"), get("effort"), get("agent")) else ModelChoice()

internal fun SavedStateHandle.setModelChoice(bot: String, choice: ModelChoice) {
    this["modelBot"] = bot
    this["model"] = choice.model
    this["effort"] = choice.effort
    this["agent"] = choice.agent
}

internal const val ModelBadRequest = "エージェント・モデル・推論の強さを選択肢から選び直してください。空のチャットが作られている場合があります"

@Composable
internal fun ModelPicker(
    api: OrcaApi, bot: String, state: SavedStateHandle, enabled: Boolean,
    trailing: @Composable () -> Unit = {},
) {
    val modelBot by state.getStateFlow<String?>("modelBot", null).collectAsState()
    val model by state.getStateFlow<String?>("model", null).collectAsState()
    val effort by state.getStateFlow<String?>("effort", null).collectAsState()
    val agent by state.getStateFlow<String?>("agent", null).collectAsState()
    val choice = if (modelBot == bot) ModelChoice(model, effort, agent) else ModelChoice()
    // Without choices the chat simply starts with the bot's default agent and model.
    val base = screenModel("options: $bot") { api.botOptions(bot) }.data
    val chosen = choice.agent?.let { id -> screenModel("options: $bot/$id") { api.botOptions(bot, id) } }
    val options = if (chosen == null) base else chosen.data
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (base != null && base.agents.size >= 2) {
            val current = choice.agent ?: base.defaultAgentOption?.id
            ChoiceMenu(
                base.agents.firstOrNull { it.id == current }?.displayName() ?: current ?: "エージェント",
                base.agents.map { it.id to it.displayName() }, current, enabled,
            ) { state.setModelChoice(bot, base.chooseAgent(it)) }
        }
        when {
            // Sending is not blocked; an empty model choice means the agent's default.
            options == null -> if (chosen != null && chosen.error == null) ValueChip("…", enabled = false, menu = false) {}
            options.models.isNotEmpty() -> ModelMenus(options, choice, enabled) { state.setModelChoice(bot, it) }
        }
        trailing()
    }
}

@Composable
private fun ModelMenus(options: BotOptions, choice: ModelChoice, enabled: Boolean, set: (ModelChoice) -> Unit) {
    val model = options.modelOption(choice.model)
    ChoiceMenu(
        model?.label ?: choice.model ?: "モデル",
        options.models.map { it.id to it.label }, choice.model ?: model?.id, enabled,
    ) { set(options.choose(it, choice.effort).copy(agent = choice.agent)) }
    val efforts = options.efforts(choice.model)
    if (efforts.isNotEmpty()) {
        val current = choice.effort ?: options.defaultEffort.takeIf { choice.model == null }
        ChoiceMenu(
            efforts.firstOrNull { it.value == current }?.label ?: current ?: "推論",
            efforts.map { it.value to it.label }, current, enabled,
        ) { set(options.chooseEffort(choice, it)) }
    }
}

/** Compact pill showing the current value; the ▾ says a tap changes it. */
@Composable
internal fun ValueChip(label: String, enabled: Boolean, menu: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // A clickable Surface keeps the 48dp touch target around the 32dp pill.
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant),
    ) {
        Text(
            if (menu) "$label ▾" else label,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A value chip with a menu that marks the current item. */
@Composable
internal fun ChoiceMenu(
    label: String,
    items: List<Pair<String?, String>>,
    selected: String?,
    enabled: Boolean,
    select: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueChip(label, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (value, text) ->
                val current = value == selected
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (current) FontWeight.Bold else null) },
                    trailingIcon = if (current) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = {
                        open = false
                        select(value)
                    },
                )
            }
        }
    }
}

/** The label of [value] (the current value by default), falling back to the raw value, then to [fallback]. */
internal fun OptionDescriptor.labelOf(value: String? = current, fallback: String = ""): String =
    choices.firstOrNull { it.value == value }?.label ?: value ?: fallback

/** The model and effort options that have choices to show, in chip order. */
internal fun ChatOptions?.chipMenus(): List<Pair<String, OptionDescriptor>> =
    listOfNotNull(this?.model?.let { "model" to it }, this?.effort?.let { "effort" to it })
        .filter { (_, option) -> option.choices.isNotEmpty() }

/** The value to send for a pick, or null when it is already current. */
internal fun OptionDescriptor.switchTarget(value: String?): String? = value?.takeIf { it != current }

/** Model and effort chips of an open chat; a pick that is already current sends nothing. */
@Composable
internal fun ChatOptionChips(options: ChatOptions?, enabled: Boolean, switch: (name: String, value: String) -> Unit) {
    val menus = options.chipMenus()
    if (menus.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        menus.forEach { (name, option) ->
            ChoiceMenu(
                option.labelOf(fallback = if (name == "model") "モデル" else "推論"),
                option.choices.map { it.value to it.label }, option.current, enabled,
            ) { value -> option.switchTarget(value)?.let { switch(name, it) } }
        }
    }
}
