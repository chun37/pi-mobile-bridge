package com.chun.pibridge

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    private val catalog = JSONObject("""{"commands":[
        {"name":"skill:local","description":"Local skill","source":"skill"},
        {"name":"model","description":"Model","source":"builtin"},
        {"name":"compact","source":"builtin"},
        {"name":"thinking","source":"builtin"}],
        "models":[{"value":"local/fixture","name":"Local"}],"thinkingLevels":["off","high"]}""")
    private fun input(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test fun onlyFirstCommandTokenShowsNameSuggestions() {
        assertEquals(listOf("model", "compact", "thinking", "skill:local"), slashOptions(input("/"), catalog).map { it.value })
        assertEquals(listOf("model"), slashOptions(input("/MO"), catalog).map { it.value })
        for (text in listOf("hello /mo", "text\n/mo", " /mo", "/compact instructions", "//path"))
            assertNull(slashQuery(input(text)))
        assertNull(slashQuery(TextFieldValue("/model", TextRange(1, 3))))
    }
    @Test fun nameCompletionPreservesArgumentsAndPlacesCursor() {
        val before = TextFieldValue("/mo  existing args", TextRange(3))
        val after = completeSlash(before, slashOptions(before, catalog).single())
        assertEquals("/model  existing args", after.text)
        assertEquals(TextRange(7), after.selection)
        val fresh = input("/com")
        assertEquals("/compact ", completeSlash(fresh, slashOptions(fresh, catalog).single()).text)
    }
    @Test fun argumentsCompleteWithoutSubmittingAndPreserveSuffix() {
        val before = TextFieldValue("/model lo trailing", TextRange(9))
        val after = completeSlash(before, slashOptions(before, catalog).single())
        assertEquals("/model local/fixture trailing", after.text)
        assertEquals(TextRange(21), after.selection)
        assertEquals(listOf("off", "high"), slashOptions(input("/thinking "), catalog).map { it.value })
        assertEquals("/thinking high ", completeSlash(input("/thinking hi"), slashOptions(input("/thinking hi"), catalog).single()).text)
    }
    @Test fun staleArgumentCandidateCannotOverwriteShorterOrOrdinaryDraft() {
        val option = slashOptions(input("/model lo"), catalog).single()
        for (text in listOf("/", "ordinary draft", "")) {
            val draft = input(text)
            assertEquals(draft, completeSlash(draft, option))
        }
    }
    @Test fun extensionOverrideDoesNotGetBuiltinArgumentCompletion() {
        val overridden = JSONObject("""{"commands":[{"name":"model","source":"extension"}],"models":[{"value":"wrong"}]}""")
        assertTrue(slashOptions(input("/model "), overridden).isEmpty())
    }
    @Test fun modelLabelShowsEveryThinkingLevelIncludingOff() {
        for (level in listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"))
            assertEquals("test-model ($level)", modelThinkingLabel(JSONObject().put("model", "test-model").put("thinkingLevel", level)))
    }
    @Test fun missingOrInvalidLevelNeverInventsASetting() {
        for (value in listOf(JSONObject.NULL, "", "   ", 42, false, JSONObject()))
            assertEquals("test-model", modelThinkingLabel(JSONObject().put("model", "test-model").put("thinkingLevel", value)))
        assertEquals("test-model", modelThinkingLabel(JSONObject().put("model", "test-model")))
        assertEquals("test-model (high)", modelThinkingLabel(JSONObject().put("model", "test-model").put("thinkingLevel", " high ")))
    }
    @Test fun missingModelKeepsSessionFallbackWithoutDanglingLevel() {
        assertEquals("同じPCセッション", modelThinkingLabel(null))
        for (value in listOf(JSONObject.NULL, "", "  ", 42))
            assertEquals("同じPCセッション", modelThinkingLabel(JSONObject().put("model", value).put("thinkingLevel", "high")))
    }
    @Test fun contextUsageIsRealAndUnknownDoesNotBecomeZero() {
        assertEquals("12.3%", contextPercent(JSONObject("""{"contextUsage":{"percent":12.345}}""")))
        assertEquals("0.0%", contextPercent(JSONObject("""{"contextUsage":{"percent":0}}""")))
        assertEquals("120.0%", contextPercent(JSONObject("""{"contextUsage":{"percent":120}}""")))
        for (value in listOf("null", "-1", "\"not-a-number\""))
            assertEquals("--%", contextPercent(JSONObject("""{"contextUsage":{"percent":$value}}""")))
        assertEquals("--%", contextPercent(null))
        assertFalse(supportsSlash(JSONObject()))
        assertTrue(supportsSlash(JSONObject("""{"capabilities":["slashCommands"]}""")))
    }
}
