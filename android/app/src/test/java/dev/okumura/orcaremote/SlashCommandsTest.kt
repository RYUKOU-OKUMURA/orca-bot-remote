package dev.okumura.orcaremote

import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    @Test fun onlyFirstSlashTokenOpensMenu() {
        assertEquals("", slashQuery("/"))
        assertEquals("Rev", slashQuery("/Rev"))
        for (text in listOf("", " /m", "途中/m", "/model ", "/m\t", "/m\n")) assertNull(slashQuery(text))
        val list = ChatCommands(listOf(SlashCommand("model", kind = "option")), listOf(ChatSkill("Modern"), ChatSkill("review")))
        assertEquals(list.commands, list.filtered("MO").commands)
        assertEquals(listOf(ChatSkill("Modern")), list.filtered("mo").skills)
        assertEquals(list, list.filtered(""))
    }

    @Test fun manualCommandsAreExactAndPreserveInstructions() {
        for (name in listOf("clear", "compact", "model", "effort")) {
            assertEquals(CommandInput(name), typedCommand("/$name "))
        }
        for (name in listOf("compact", "model", "effort")) {
            assertEquals(CommandInput(name, "指示 と 値"), typedCommand("/$name  指示 と 値 "))
        }
        assertEquals(CommandInput("compact", "一行\n二行"), typedCommand("/compact 一行\n二行"))
        for (text in listOf("本文", "途中 /clear", " /clear", "/clear extra", "/models", "/help", "/review")) {
            assertNull(typedCommand(text))
        }
    }

    @Test fun textCommandMatchesFirstTokenEvenWithArguments() {
        val catalog = ChatCommands(listOf(
            SlashCommand("help", kind = "text"),
            SlashCommand("model", kind = "option"),
        ), emptyList())
        for (text in listOf("/help", "/help foo", "/help\tfoo", "/help\nfoo")) assertTrue(catalog.isTextCommand(text))
        for (text in listOf(" /help foo", "/helper foo", "/model foo", "see /help foo")) assertFalse(catalog.isTextCommand(text))
        assertFalse((null as ChatCommands?).isTextCommand("/help foo"))
    }

    @Test fun optionMatchesValueOrLabel() {
        val option = OptionDescriptor("gpt", listOf(OptionChoice("gpt", "GPT Example")))
        assertEquals("gpt", option.resolveValue("gpt"))
        assertEquals("gpt", option.resolveValue("GPT example"))
        assertNull(option.resolveValue("unknown"))
        assertEquals("gpt", null.resolveValue("gpt"))
    }
}
