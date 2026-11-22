package dev.okumura.orcaremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle

/** null means "keep the bot's default", which is not sent to the server. */
internal data class ModelChoice(val model: String? = null, val effort: String? = null)

internal fun BotOptions.modelOption(model: String?): BotModelOption? =
    if (model != null) models.firstOrNull { it.id == model }
    else models.firstOrNull { it.id == defaultModel } ?: models.firstOrNull { it.isDefault }

internal fun BotOptions.efforts(model: String?): List<OptionChoice> = modelOption(model)?.efforts.orEmpty()

/** Picks a model and drops an effort the new model does not offer. */
internal fun BotOptions.choose(model: String?, effort: String?) =
    ModelChoice(model, effort?.takeIf { value -> efforts(model).any { it.value == value } })

// The choice belongs to one bot. Another bot starts from its defaults.
internal fun SavedStateHandle.modelChoice(bot: String): ModelChoice =
    if (get<String>("modelBot") == bot) ModelChoice(get("model"), get("effort")) else ModelChoice()

internal fun SavedStateHandle.setModelChoice(bot: String, choice: ModelChoice) {
    this["modelBot"] = bot
    this["model"] = choice.model
    this["effort"] = choice.effort
}

internal const val ModelBadRequest = "モデルまたは推論の強さを選択肢から選び直してください。空のチャットが作られている場合があります"

@Composable
internal fun ModelPicker(api: OrcaApi, bot: String, state: SavedStateHandle, enabled: Boolean) {
    // Without choices the chat simply starts with the default model.
    val options = screenModel("options: $bot") { api.botOptions(bot) }.data?.takeIf { it.models.isNotEmpty() } ?: return
    val modelBot by state.getStateFlow<String?>("modelBot", null).collectAsState()
    val model by state.getStateFlow<String?>("model", null).collectAsState()
    val effort by state.getStateFlow<String?>("effort", null).collectAsState()
    val choice = if (modelBot == bot) ModelChoice(model, effort) else ModelChoice()
    val defaultModel = options.modelOption(null)?.label
    FlowRow(horizontalArrangement = Arrangement.spacedBy(OrcaGap)) {
        ChoiceMenu(
            "モデル: " + if (choice.model != null) options.modelOption(choice.model)?.label ?: choice.model
                else defaultModel?.let { "既定（$it）" } ?: "既定",
            listOf(null to "既定") + options.models.map { it.id to it.label }, enabled,
        ) { state.setModelChoice(bot, options.choose(it, choice.effort)) }
        val efforts = options.efforts(choice.model)
        if (efforts.isNotEmpty()) {
            val defaultEffort = options.defaultEffort.takeIf { choice.model == null }
                ?.let { value -> efforts.firstOrNull { it.value == value }?.label ?: value }
            ChoiceMenu(
                "推論の強さ: " + (efforts.firstOrNull { it.value == choice.effort }?.label
                    ?: defaultEffort?.let { "既定（$it）" } ?: "既定"),
                listOf(null to "既定") + efforts.map { it.value to it.label }, enabled,
            ) { state.setModelChoice(bot, choice.copy(effort = it)) }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, items: List<Pair<String?, String>>, enabled: Boolean, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OrcaOutlinedButton({ open = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    select(value)
                })
            }
        }
    }
}
