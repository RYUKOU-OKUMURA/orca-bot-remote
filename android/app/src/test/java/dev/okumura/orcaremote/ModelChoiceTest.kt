package dev.okumura.orcaremote

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class ModelChoiceTest {
    private val options = apiJson.decodeFromString<BotOptions>("""{"models":[
        {"id":"opus","label":"Opus","isDefault":true,"efforts":[{"value":"high","label":"高"},{"value":"low","label":"低"}]},
        {"id":"sonnet","label":"Sonnet","isDefault":false,"efforts":[{"value":"low","label":"低"}]},
        {"id":"haiku","label":"Haiku","isDefault":false,"efforts":[]}],
        "defaultModel":"sonnet","defaultEffort":null}""")

    @Test fun parsesOptionsAndEmptyResult() {
        assertEquals(BotOptions(), apiJson.decodeFromString<BotOptions>("""{"models":[],"defaultModel":null,"defaultEffort":null}"""))
        assertEquals(3, options.models.size)
        assertTrue(options.models.first().isDefault)
    }

    @Test fun effortsFollowTheChosenOrDefaultModel() {
        // defaultModel (the chat's current model) wins over isDefault.
        assertEquals(listOf("low"), options.efforts(null).map { it.value })
        assertEquals(listOf("high", "low"), options.efforts("opus").map { it.value })
        assertEquals(emptyList<OptionChoice>(), options.efforts("haiku"))
        assertEquals(emptyList<OptionChoice>(), options.efforts("gone"))
        assertEquals("opus", options.copy(defaultModel = null).modelOption(null)?.id)
    }

    @Test fun changingModelDropsUnsupportedEffort() {
        assertEquals(ModelChoice("opus", "low"), options.choose("opus", "low"))
        assertEquals(ModelChoice("opus", "high"), options.choose("opus", "high"))
        assertEquals(ModelChoice("haiku", null), options.choose("haiku", "low"))
        assertEquals(ModelChoice(null, "low"), options.choose(null, "low"))
        assertEquals(ModelChoice(), options.choose(null, "high"))
    }

    @Test fun pickingTheDefaultModelAgainSendsNull() {
        // sonnet is the default model, so the menu (which has no separate 既定 row) maps it back to null.
        assertEquals(ModelChoice(null, "low"), options.choose("sonnet", "low"))
        assertEquals(ModelChoice(), options.choose("sonnet", "high"))
    }

    @Test fun pickingTheDefaultEffortAgainSendsNull() {
        val withDefault = options.copy(defaultEffort = "low")
        assertEquals(ModelChoice(), withDefault.chooseEffort(ModelChoice(), "low"))
        assertEquals(ModelChoice(effort = "high"), withDefault.chooseEffort(ModelChoice(), "high"))
        // The default effort belongs to the default model; with another model it is an explicit choice.
        assertEquals(ModelChoice("opus", "low"), withDefault.chooseEffort(ModelChoice("opus"), "low"))
        assertEquals(ModelChoice(effort = "low", agent = "codex"), options.chooseEffort(ModelChoice(agent = "codex"), "low"))
    }

    @Test fun agentsShowTheirCliNames() {
        fun name(id: String, label: String) = BotAgentOption(id, label).displayName()
        assertEquals("Claude Code", name("claude", "claude"))
        assertEquals("Claude Code", name("claude-x", "Claude"))
        assertEquals("Codex", name("codex", "codex"))
        assertEquals("Antigravity", name("antigravity", "agy"))
        assertEquals("OpenCode", name("opencode", "opencode"))
        assertEquals("Hermes", name("hermes", "hermes"))
        assertEquals("Devin", name("devin", "Devin"))
    }

    @Test fun choiceIsKeptPerBotAndDefaultsToNothing() {
        val state = SavedStateHandle()
        assertEquals(ModelChoice(), state.modelChoice("A"))
        state.setModelChoice("A", ModelChoice("opus", "high"))
        assertEquals(ModelChoice("opus", "high"), state.modelChoice("A"))
        assertEquals(ModelChoice(), state.modelChoice("B"))
        state.setModelChoice("A", ModelChoice(agent = "codex"))
        assertEquals(ModelChoice(agent = "codex"), state.modelChoice("A"))
        state.setModelChoice("B", ModelChoice("gpt", agent = "codex"))
        assertEquals(ModelChoice(), state.modelChoice("A"))
    }

    @Test fun agentsParseAndChangingAgentResetsModel() {
        val agents = apiJson.decodeFromString<BotOptions>("""{"agents":[{"id":"claude","label":"Claude","isDefault":true},
            {"id":"codex","label":"Codex","isDefault":false},{"id":"hermes","label":"hermes","isDefault":false}],
            "agent":"codex","defaultAgent":"claude","models":[],"defaultModel":null,"defaultEffort":null}""")
        assertEquals(listOf("claude", "codex", "hermes"), agents.agents.map { it.id })
        assertEquals("codex", agents.agent)
        assertEquals("Claude", agents.defaultAgentOption?.label)
        assertEquals(ModelChoice(agent = "codex"), agents.chooseAgent("codex"))
        // The bot's own agent is the default and is not sent.
        assertEquals(ModelChoice(), agents.chooseAgent("claude"))
        assertEquals(ModelChoice(), agents.chooseAgent(null))
        assertEquals(ModelChoice(), agents.copy(agents = emptyList()).chooseAgent("claude"))
        assertEquals(emptyList<BotAgentOption>(), options.agents)
    }

    @Test fun chatOptionLabelFallsBackToValueThenName() {
        val option = OptionDescriptor("high", listOf(OptionChoice("high", "高"), OptionChoice("low", "低")))
        assertEquals("高", option.labelOf(fallback = "推論"))
        assertEquals("低", option.labelOf("low"))
        assertEquals("max", option.copy(current = "max").labelOf(fallback = "推論"))
        assertEquals("モデル", option.copy(current = null).labelOf(fallback = "モデル"))
    }

    @Test fun chatOptionChipsSkipEmptyOptionsAndCurrentPicks() {
        val effort = OptionDescriptor("high", listOf(OptionChoice("high", "高"), OptionChoice("low", "低")))
        assertEquals(listOf("effort"), ChatOptions(OptionDescriptor("opus"), effort).chipMenus().map { it.first })
        assertEquals(listOf("model", "effort"), ChatOptions(effort, effort).chipMenus().map { it.first })
        assertTrue(ChatOptions().chipMenus().isEmpty())
        assertTrue((null as ChatOptions?).chipMenus().isEmpty())
        assertNull(effort.switchTarget("high"))
        assertNull(effort.switchTarget(null))
        assertEquals("low", effort.switchTarget("low"))
    }
}
