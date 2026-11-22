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
        assertEquals(ModelChoice("sonnet", "low"), options.choose("sonnet", "low"))
        assertEquals(ModelChoice("sonnet", null), options.choose("sonnet", "high"))
        assertEquals(ModelChoice("haiku", null), options.choose("haiku", "low"))
        assertEquals(ModelChoice(null, "low"), options.choose(null, "low"))
        assertEquals(ModelChoice(), options.choose(null, "high"))
    }

    @Test fun choiceIsKeptPerBotAndDefaultsToNothing() {
        val state = SavedStateHandle()
        assertEquals(ModelChoice(), state.modelChoice("A"))
        state.setModelChoice("A", ModelChoice("opus", "high"))
        assertEquals(ModelChoice("opus", "high"), state.modelChoice("A"))
        assertEquals(ModelChoice(), state.modelChoice("B"))
    }
}
